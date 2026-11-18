package com.example.orders;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ApiClient apiClient;
    private final RabbitTemplate rabbitTemplate;

    public OrderService(OrderRepository orderRepository, ApiClient apiClient, RabbitTemplate rabbitTemplate) {
        this.orderRepository = orderRepository;
        this.apiClient = apiClient;
        this.rabbitTemplate = rabbitTemplate;
    }

    public record PlaceOrderRequest(long customerId, String sku, int quantity, BigDecimal amount, String email) {}

    public List<OrderEntity> getOrders() {
        return orderRepository.findAll();
    }

    public OrderEntity getOrder(long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
    }

    @Transactional
    public long placeOrder(PlaceOrderRequest request) throws IOException, InterruptedException {
        OrderEntity order = orderRepository.saveAndFlush(
                new OrderEntity(request.customerId(), request.sku(), request.quantity(), request.amount(), request.email()));

        // http call
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

        rabbitTemplate.convertAndSend(
                "orders", "order.paid", new OrderPaidListener.OrderPaidEvent(order.getId(), paymentId, order.getAmount()));

        return order.getId();
    }
}
