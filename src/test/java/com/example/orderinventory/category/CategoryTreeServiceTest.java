package com.example.orderinventory.category;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import jakarta.persistence.EntityManagerFactory;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * So sanh de quy o tang Java (1 + N query) voi mot cau WITH RECURSIVE duy
 * nhat de lay toan bo subtree cua mot Category.
 * Chay: mvn test -Dtest=CategoryTreeServiceTest
 *
 * Cay du lieu dung cho test (6 node):
 *   Electronics (root)
 *   +-- Phones
 *   |    +-- Android
 *   |    +-- iOS
 *   +-- Laptops
 *        +-- Gaming Laptops
 */
@DataJpaTest
@Import(CategoryTreeService.class)
class CategoryTreeServiceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CategoryTreeService categoryTreeService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Long rootId;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void seedTree() {
        Category root = new Category("Electronics", null);
        entityManager.persist(root); // IDENTITY -> insert ngay, co id ngay lap tuc

        Category phones = new Category("Phones", root);
        entityManager.persist(phones);
        Category laptops = new Category("Laptops", root);
        entityManager.persist(laptops);

        entityManager.persist(new Category("Android", phones));
        entityManager.persist(new Category("iOS", phones));
        entityManager.persist(new Category("Gaming Laptops", laptops));

        entityManager.flush();
        entityManager.clear();

        rootId = root.getId();
    }

    @Test
    void de_quy_o_tang_java_can_1_cong_N_query() {
        statistics().clear();

        CategoryTreeDto tree = categoryTreeService.getSubtreeRecursive(rootId);

        assertThat(collectNames(tree)).containsExactlyInAnyOrder(
                "Electronics", "Phones", "Laptops", "Android", "iOS", "Gaming Laptops");

        // 1 query tim root (findById) + 6 query tim children cho TUNG node
        // (ke ca 3 node la, tra ve danh sach rong) = 7.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(7);
    }

    @Test
    void recursive_cte_chi_can_dung_1_query_du_cay_co_bao_nhieu_tang() {
        statistics().clear();

        CategoryTreeDto tree = categoryTreeService.getSubtreeWithRecursiveCte(rootId);

        assertThat(collectNames(tree)).containsExactlyInAnyOrder(
                "Electronics", "Phones", "Laptops", "Android", "iOS", "Gaming Laptops");

        // WITH RECURSIVE de DB tu duyet toan bo cay trong MOT lan round-trip.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void ca_hai_cach_cho_ra_dung_cau_truc_cay_nhu_nhau() {
        CategoryTreeDto viaRecursive = categoryTreeService.getSubtreeRecursive(rootId);
        CategoryTreeDto viaCte = categoryTreeService.getSubtreeWithRecursiveCte(rootId);

        assertThat(childNamesOf(viaRecursive, "Phones"))
                .containsExactlyInAnyOrderElementsOf(childNamesOf(viaCte, "Phones"));
        assertThat(childNamesOf(viaRecursive, "Laptops"))
                .containsExactlyInAnyOrderElementsOf(childNamesOf(viaCte, "Laptops"));
    }

    private Set<String> collectNames(CategoryTreeDto dto) {
        Set<String> names = new HashSet<>();
        names.add(dto.getName());
        dto.getChildren().forEach(child -> names.addAll(collectNames(child)));
        return names;
    }

    private Set<String> childNamesOf(CategoryTreeDto tree, String nodeName) {
        if (tree.getName().equals(nodeName)) {
            Set<String> names = new HashSet<>();
            tree.getChildren().forEach(c -> names.add(c.getName()));
            return names;
        }
        for (CategoryTreeDto child : tree.getChildren()) {
            Set<String> found = childNamesOf(child, nodeName);
            if (!found.isEmpty() || child.getName().equals(nodeName)) {
                return found;
            }
        }
        return Set.of();
    }
}
