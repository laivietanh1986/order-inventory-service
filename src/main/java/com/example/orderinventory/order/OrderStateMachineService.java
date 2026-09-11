package com.example.orderinventory.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ban DUNG (muc 21): day dieu kien chuyen trang thai XUONG chinh cau
 * {@code UPDATE} (xem {@link OrderRepository#updateStatusIfCurrentlyIs}),
 * kiem tra bang affected rows thay vi "doc roi kiem tra trong bo nho" nhu
 * {@link NaiveOrderStateMachineService}. Postgres tu dam bao tinh atomic
 * cua "doc-kiem tra-ghi" ngay trong 1 cau SQL (row bi khoa trong luc UPDATE
 * chay), nen khong bao gio co chuyen hai lenh goi doc lap cung "thay" duoc
 * CREATED.
 *
 * confirm()/cancel() deu tra ve TRANG THAI CUOI CUNG co dung ket qua mong
 * muon hay khong (vi du cancel() tra ve true neu SAU CUNG order dang o
 * trang thai CANCELLED, bat ke chinh loi goi nay hay mot loi goi truoc do da
 * thuc hien buoc chuyen) - day la thu tao nen tinh IDEMPOTENT: goi cancel()
 * nhieu lan tren mot order da CANCELLED van tra ve cung mot ket qua (true),
 * khong nem loi, khong tao them ban ghi lich su nao.
 */
@Service
public class OrderStateMachineService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public OrderStateMachineService(OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    }

    @Transactional
    public boolean confirm(Long orderId) {
        return transitionIfCreated(orderId, "CONFIRMED");
    }

    @Transactional
    public boolean cancel(Long orderId) {
        return transitionIfCreated(orderId, "CANCELLED");
    }

    private boolean transitionIfCreated(Long orderId, String targetStatus) {
        int affectedRows = orderRepository.updateStatusIfCurrentlyIs(orderId, "CREATED", targetStatus);
        if (affectedRows == 1) {
            OrderStatusHistory history = new OrderStatusHistory(targetStatus);
            history.setOrder(orderRepository.getReferenceById(orderId));
            orderStatusHistoryRepository.save(history);
        }
        // Doc lai trang thai HIEN TAI (co the da duoc chinh loi goi nay doi,
        // hoac da la targetStatus tu truoc do roi) - day la co so cho tinh
        // idempotent: ket qua phan anh TRANG THAI THUC TE, khong phai "loi
        // goi nay co lam gi khong".
        return targetStatus.equals(orderRepository.findStatusById(orderId));
    }
}
