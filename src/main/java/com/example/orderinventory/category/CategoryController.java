package com.example.orderinventory.category;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryTreeService categoryTreeService;

    public CategoryController(CategoryTreeService categoryTreeService) {
        this.categoryTreeService = categoryTreeService;
    }

    /**
     * strategy=recursive dung de quy o tang Java (1 + N query).
     * strategy=cte (mac dinh) dung WITH RECURSIVE, chi 1 query du cay lon co nhieu tang.
     */
    @GetMapping("/{id}/subtree")
    public CategoryTreeDto getSubtree(
            @PathVariable Long id,
            @RequestParam(defaultValue = "cte") String strategy) {
        return "recursive".equalsIgnoreCase(strategy)
                ? categoryTreeService.getSubtreeRecursive(id)
                : categoryTreeService.getSubtreeWithRecursiveCte(id);
    }
}
