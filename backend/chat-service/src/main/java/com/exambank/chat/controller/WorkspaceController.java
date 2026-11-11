package com.exambank.chat.controller;

import com.exambank.common.dto.ContextRef;
import com.exambank.common.entity.WorkspaceItem;
import com.exambank.common.repository.WorkspaceItemRepository;
import com.exambank.common.security.DocumentContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/workspace/items")
@PreAuthorize("hasRole('STUDENT')")
public class WorkspaceController {
    private final WorkspaceItemRepository items;
    private final DocumentContextService contexts;
    private final ObjectMapper mapper;
    public WorkspaceController(WorkspaceItemRepository items, DocumentContextService contexts, ObjectMapper mapper) {
        this.items = items; this.contexts = contexts; this.mapper = mapper;
    }
    public record SaveRequest(@Pattern(regexp = "note|question") @NotNull String kind,
                              @NotBlank @Size(max = 160) String title,
                              @NotBlank @Size(max = 60000) String content,
                              @NotNull @Size(min = 1, max = 8) List<@Valid ContextRef> contextRefs) {}

    @PostMapping
    public WorkspaceItem save(@Valid @RequestBody SaveRequest request, Authentication auth) throws Exception {
        var resolved = contexts.resolve(request.contextRefs(), null, null);
        WorkspaceItem item = new WorkspaceItem();
        item.owner = auth.getName(); item.kind = request.kind(); item.title = request.title();
        item.content = request.content(); item.referencesJson = mapper.writeValueAsString(resolved.references());
        return items.save(item);
    }

    @GetMapping
    public org.springframework.data.domain.Page<WorkspaceItem> list(Authentication auth,
            @RequestParam(defaultValue = "0") int page) {
        return items.findByOwnerOrderByCreatedAtDesc(auth.getName(), PageRequest.of(Math.max(0, page), 30));
    }
}
