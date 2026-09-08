package com.example.orderinventory.category;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiem tra rieng tang web: endpoint tra dung JSON va chon dung strategy.
 * spring-boot-starter-security co san trong project nhung chua co
 * SecurityFilterChain nao duoc cau hinh, nen loai auto-config security ra
 * khoi slice test nay de tap trung vao logic cua controller.
 */
@WebMvcTest(
        controllers = CategoryController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryTreeService categoryTreeService;

    @Test
    void mac_dinh_dung_chien_luoc_cte() throws Exception {
        CategoryTreeDto root = new CategoryTreeDto(1L, "Electronics");
        CategoryTreeDto child = new CategoryTreeDto(2L, "Phones");
        root.addChild(child);
        when(categoryTreeService.getSubtreeWithRecursiveCte(1L)).thenReturn(root);

        mockMvc.perform(get("/api/categories/1/subtree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Electronics"))
                .andExpect(jsonPath("$.children[0].name").value("Phones"));

        verify(categoryTreeService).getSubtreeWithRecursiveCte(1L);
    }

    @Test
    void strategy_recursive_goi_dung_phuong_thuc_de_quy() throws Exception {
        when(categoryTreeService.getSubtreeRecursive(1L))
                .thenReturn(new CategoryTreeDto(1L, "Electronics"));

        mockMvc.perform(get("/api/categories/1/subtree").param("strategy", "recursive"))
                .andExpect(status().isOk());

        verify(categoryTreeService).getSubtreeRecursive(1L);
    }
}
