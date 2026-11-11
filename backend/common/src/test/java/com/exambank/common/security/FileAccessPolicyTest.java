package com.exambank.common.security;

import com.exambank.common.entity.ExamFile;
import com.exambank.common.repository.ExamFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.*;

class FileAccessPolicyTest {
    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void nonEmptyUnknownSelectionFailsClosed() {
        FileAccessPolicy policy = policy(Map.of());
        assertThatThrownBy(() -> policy.resolvePublicSelection(List.of(99L), null))
                .isInstanceOf(InvalidFileSelectionException.class);
    }

    @Test
    void mixedValidAndInvalidSelectionFailsAsOneUnit() {
        ExamFile publicFile = file(1, "PUBLIC", "/public.pdf");
        FileAccessPolicy policy = policy(Map.of(1L, publicFile));
        assertThatThrownBy(() -> policy.resolvePublicSelection(List.of(1L, 2L), null))
                .isInstanceOf(InvalidFileSelectionException.class);
    }

    @Test
    void privateSelectionIsRejectedEvenForAuthenticatedStudent() {
        authenticate("STUDENT");
        ExamFile privateFile = file(2, "PRIVATE", "/private.pdf");
        FileAccessPolicy policy = policy(Map.of(2L, privateFile));
        assertThatThrownBy(() -> policy.resolvePublicSelection(List.of(2L), null))
                .isInstanceOf(InvalidFileSelectionException.class);
        assertThat(policy.readableById(2L)).isEmpty();
    }

    @Test
    void serviceCanReadPrivateFileButPublicSelectionStillCannotBroaden() {
        authenticate("SERVICE");
        ExamFile privateFile = file(2, "PRIVATE", "/private.pdf");
        FileAccessPolicy policy = policy(Map.of(2L, privateFile));
        assertThat(policy.readableById(2L)).contains(privateFile);
        assertThatThrownBy(() -> policy.resolvePublicSelection(List.of(2L), null))
                .isInstanceOf(InvalidFileSelectionException.class);
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("test", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private ExamFile file(long id, String scope, String path) {
        ExamFile file = new ExamFile();
        file.setId(id); file.setAccessScope(scope); file.setFilePath(path); file.setFileName(path.substring(1));
        return file;
    }

    private FileAccessPolicy policy(Map<Long, ExamFile> files) {
        ExamFileRepository repository = (ExamFileRepository) Proxy.newProxyInstance(
                ExamFileRepository.class.getClassLoader(),
                new Class<?>[]{ExamFileRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findAllById" -> {
                        @SuppressWarnings("unchecked")
                        Iterable<Long> ids = (Iterable<Long>) args[0];
                        java.util.ArrayList<ExamFile> found = new java.util.ArrayList<>();
                        ids.forEach(id -> Optional.ofNullable(files.get(id)).ifPresent(found::add));
                        yield found;
                    }
                    case "findById" -> Optional.ofNullable(files.get((Long) args[0]));
                    case "findByFilePath" -> files.values().stream()
                            .filter(file -> file.getFilePath().equals(args[0]))
                            .findFirst();
                    case "toString" -> "InMemoryExamFileRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new FileAccessPolicy(repository);
    }
}
