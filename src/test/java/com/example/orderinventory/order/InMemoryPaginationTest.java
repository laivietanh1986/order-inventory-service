package com.example.orderinventory.order;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HHH000104: JOIN FETCH mot collection ket hop Pageable buoc Hibernate nap
 * TOAN BO ket qua vao JVM roi moi cat lay dung trang. Seed 10.000 order (moi
 * order 1 item) de con so "toan bo ket qua" va "1 trang 20 phan tu" khac biet
 * ro rang.
 * Chay: mvn test -Dtest=InMemoryPaginationTest
 */
@DataJpaTest
@Import(OrderPageService.class)
class InMemoryPaginationTest {

    private static final String HIBERNATE_QUERY_LOGGER = "org.hibernate.orm.query";
    private static final String IN_MEMORY_PAGINATION_WARNING =
            "firstResult/maxResults specified with collection fetch; applying in memory";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderPageService orderPageService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private ListAppender<ILoggingEvent> logAppender;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void seed10000Orders() {
        for (int i = 0; i < 10_000; i++) {
            Order order = new Order("Customer " + i, "CREATED");
            order.addItem(new OrderItem("SKU-" + i, 1));
            entityManager.persist(order);
            if (i % 500 == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        entityManager.flush();
        entityManager.clear();

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(HIBERNATE_QUERY_LOGGER)).addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(HIBERNATE_QUERY_LOGGER)).detachAppender(logAppender);
    }

    @Test
    void join_fetch_voi_pageable_nap_toan_bo_10000_order_roi_moi_cat_con_20() {
        statistics().clear();
        Pageable pageable = PageRequest.of(0, 20);

        Page<Order> page = orderRepository.findAllWithItemsJoinFetchPaged(pageable);

        // Page "nhin ben ngoai" van dung: dung 20 phan tu, tong so dung 10.000.
        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getTotalElements()).isEqualTo(10_000);

        // NHUNG cau SELECT thuc su (khong tinh count query) da tra ve TOAN BO
        // 10.000 row - khong he co LIMIT/OFFSET nao duoc ap dung o tang SQL.
        // Hibernate tu cat lay 20 phan tu dau SAU KHI da nap het vao JVM.
        String selectHql = "SELECT o FROM Order o JOIN FETCH o.items";
        assertThat(statistics().getQueryStatistics(selectHql).getExecutionRowCount()).isEqualTo(10_000);

        // Canh bao HHH duoc Hibernate log dung nhu du kien - khong con la
        // "cam giac cham", ma la mot dong log bat duoc va assert duoc.
        assertThat(logAppender.list)
                .anyMatch(event -> event.getFormattedMessage().contains(IN_MEMORY_PAGINATION_WARNING));
    }

    @Test
    void two_query_pattern_chi_nap_dung_20_order_khong_con_canh_bao() {
        statistics().clear();
        Pageable pageable = PageRequest.of(0, 20);

        Page<Order> page = orderPageService.findOrdersWithItemsPaged(pageable);

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getTotalElements()).isEqualTo(10_000);
        for (Order order : page.getContent()) {
            assertThat(order.getItems()).hasSize(1);
        }

        // Query 2 (JOIN FETCH WHERE id IN :ids) chi tra ve dung 20 row - vi no
        // khong con Pageable/firstResult/maxResults, chi bi gioi han boi danh
        // sach id (da duoc phan trang tu query 1) truyen vao.
        String selectHql = "SELECT DISTINCT o FROM Order o JOIN FETCH o.items WHERE o.id IN :ids";
        assertThat(statistics().getQueryStatistics(selectHql).getExecutionRowCount()).isEqualTo(20);

        // Khong con canh bao "applying in memory" nao duoc log.
        assertThat(logAppender.list)
                .noneMatch(event -> event.getFormattedMessage().contains(IN_MEMORY_PAGINATION_WARNING));
    }
}
