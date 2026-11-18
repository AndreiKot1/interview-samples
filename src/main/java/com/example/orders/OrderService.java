package com.example.orders;

import java.io.IOException;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ApiClient apiClient;

    public OrderService(OrderRepository orderRepository, ApiClient apiClient) {
        this.orderRepository = orderRepository;
        this.apiClient = apiClient;
    }

    public record PlaceOrderRequest(long customerId, String sku, int quantity, BigDecimal amount, String email) {}

    @Transactional
    public long placeOrder(PlaceOrderRequest request) throws IOException, InterruptedException {
        OrderEntity order = orderRepository.saveAndFlush(
                new OrderEntity(request.customerId(), request.sku(), request.quantity(), request.amount(), request.email()));

        apiClient.reserveStock(order);

        String paymentId = apiClient.chargeCustomer(order);
        order.setPaymentId(paymentId);
        order.setStatus("PAID");
        orderRepository.save(order);

        try {
            apiClient.sendConfirmationMail(order);
        } catch (Exception e) {
            log.warn("Could not send mail for order {}", order.getId());
        }

        if (order.getQuantity() > 100) {
            throw new IllegalStateException("Bulk orders need manual approval");
        }

        return order.getId();
    }

    public OrderEntity getOrder(long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
    }
}
