package com.example.orderinventory.category;

import java.util.ArrayList;
import java.util.List;

public class CategoryTreeDto {

    private final Long id;
    private final String name;
    private final List<CategoryTreeDto> children = new ArrayList<>();

    public CategoryTreeDto(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public void addChild(CategoryTreeDto child) {
        children.add(child);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<CategoryTreeDto> getChildren() {
        return children;
    }
}
