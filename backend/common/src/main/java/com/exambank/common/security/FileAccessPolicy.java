package com.exambank.common.security;

import com.exambank.common.entity.ExamFile;
import com.exambank.common.repository.ExamFileRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.*;

/** Central file policy. Students can read only PUBLIC files; privileged internal/admin roles can read all. */
@Component
public class FileAccessPolicy {
    public static final String PUBLIC = "PUBLIC";
    private final ExamFileRepository files;

    public FileAccessPolicy(ExamFileRepository files) {
        this.files = files;
    }

    public boolean isPrivileged() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream().map(a -> a.getAuthority()).anyMatch(a ->
                a.equals("ROLE_SERVICE") || a.equals("ROLE_SUPER_ADMIN") || a.equals("ROLE_ADMIN")
                        || a.equals("ROLE_EDITOR") || a.equals("ROLE_VIEWER"));
    }

    public boolean canRead(ExamFile file) {
        return file != null && (isPrivileged() || PUBLIC.equals(file.getAccessScope()));
    }

    public Optional<ExamFile> readableById(Long id) {
        if (id == null) return Optional.empty();
        return files.findById(id).filter(this::canRead);
    }

    public Optional<ExamFile> readableByPath(String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        return files.findByFilePath(path).filter(this::canRead);
    }

    /**
     * Resolve IDs or the one-release legacy paths. Empty input means global PUBLIC retrieval.
     * Any mismatch fails the whole selection; it must never degrade into an empty filter.
     */
    public List<ExamFile> resolvePublicSelection(List<Long> ids, List<String> legacyPaths) {
        boolean hasIds = ids != null && !ids.isEmpty();
        boolean hasPaths = legacyPaths != null && !legacyPaths.isEmpty();
        if (hasIds && hasPaths) throw new InvalidFileSelectionException();
        if (!hasIds && !hasPaths) return List.of();

        int size = hasIds ? ids.size() : legacyPaths.size();
        if (size > 100) throw new InvalidFileSelectionException();
        LinkedHashMap<Long, ExamFile> resolved = new LinkedHashMap<>();
        if (hasIds) {
            if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size())
                throw new InvalidFileSelectionException();
            Map<Long, ExamFile> byId = new HashMap<>();
            files.findAllById(ids).forEach(file -> byId.put(file.getId(), file));
            for (Long id : ids) {
                ExamFile file = byId.get(id);
                if (file == null || !PUBLIC.equals(file.getAccessScope())) throw new InvalidFileSelectionException();
                resolved.put(id, file);
            }
        } else {
            if (legacyPaths.stream().anyMatch(p -> p == null || p.isBlank())
                    || new HashSet<>(legacyPaths).size() != legacyPaths.size())
                throw new InvalidFileSelectionException();
            for (String path : legacyPaths) {
                ExamFile file = files.findByFilePath(path).orElseThrow(InvalidFileSelectionException::new);
                if (!PUBLIC.equals(file.getAccessScope()) || resolved.put(file.getId(), file) != null)
                    throw new InvalidFileSelectionException();
            }
        }
        if (resolved.size() != size) throw new InvalidFileSelectionException();
        return List.copyOf(resolved.values());
    }
}
