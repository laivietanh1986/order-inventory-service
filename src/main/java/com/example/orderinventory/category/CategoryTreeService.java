package com.example.orderinventory.category;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CategoryTreeService {

    private final CategoryRepository categoryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public CategoryTreeService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Cach 1: de quy o tang Java. Voi mot cay N node, can (1 + N) cau SELECT:
     * 1 lan tim root, va 1 lan tim children cho MOI node (ke ca la, tra ve
     * danh sach rong). Don gian, de doc, nhung so query ti le thuan voi kich
     * thuoc cay.
     */
    public CategoryTreeDto getSubtreeRecursive(Long rootId) {
        Category root = categoryRepository.findById(rootId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + rootId));
        return buildRecursive(root);
    }

    private CategoryTreeDto buildRecursive(Category category) {
        CategoryTreeDto dto = new CategoryTreeDto(category.getId(), category.getName());
        List<Category> children = categoryRepository.findByParentCategoryId(category.getId()); // 1 query/node
        for (Category child : children) {
            dto.addChild(buildRecursive(child));
        }
        return dto;
    }

    /**
     * Cach 2: mot cau SQL duy nhat dung WITH RECURSIVE (recursive CTE). DB tu
     * lam het viec "duyet cay" o tang engine, ung dung chi nhan ve mot danh
     * sach phang (id, name, parent_id, depth) roi dung lai thanh cay trong
     * bo nho - KHONG can query them lan nao nua du cay lon co nhieu tang.
     */
    @SuppressWarnings("unchecked")
    public CategoryTreeDto getSubtreeWithRecursiveCte(Long rootId) {
        String sql = """
                WITH RECURSIVE subtree (id, name, parent_id, depth) AS (
                    SELECT id, name, parent_id, 0 AS depth
                    FROM categories
                    WHERE id = :rootId
                    UNION ALL
                    SELECT c.id, c.name, c.parent_id, s.depth + 1
                    FROM categories c
                    JOIN subtree s ON c.parent_id = s.id
                )
                SELECT id, name, parent_id, depth FROM subtree ORDER BY depth
                """;

        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("rootId", rootId)
                .getResultList();

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Category not found: " + rootId);
        }

        Map<Long, CategoryTreeDto> dtoById = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            String name = (String) row[1];
            Long parentId = row[2] == null ? null : ((Number) row[2]).longValue();

            CategoryTreeDto dto = new CategoryTreeDto(id, name);
            dtoById.put(id, dto);

            // ORDER BY depth dam bao parent luon da co trong map truoc con no
            // (tru chinh root, khong co parent trong ket qua nay).
            if (parentId != null && dtoById.containsKey(parentId) && !id.equals(rootId)) {
                dtoById.get(parentId).addChild(dto);
            }
        }

        return dtoById.get(rootId);
    }
}
