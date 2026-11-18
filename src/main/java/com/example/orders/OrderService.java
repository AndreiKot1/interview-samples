package com.example.orders;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public record PlaceOrderRequest(long customerId, String sku, int quantity, BigDecimal amount, String email) {}

    @Transactional
    public long placeOrder(PlaceOrderRequest request) throws IOException, InterruptedException {
        OrderEntity order = orderRepository.saveAndFlush(
                new OrderEntity(request.customerId(), request.sku(), request.quantity(), request.amount(), request.email()));

        HttpRequest reserveRequest = HttpRequest.newBuilder(URI.create("https://inventory.example.com/api/v1/reservations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(String.format(
                        "{\"orderId\":%d,\"sku\":\"%s\",\"quantity\":%d}",
                        order.getId(), order.getSku(), order.getQuantity())))
                .build();
        HttpResponse<String> reserveResponse = httpClient.send(reserveRequest, HttpResponse.BodyHandlers.ofString());
        if (reserveResponse.statusCode() != 200) {
            throw new IllegalStateException("Stock reservation failed: " + reserveResponse.statusCode());
        }

        HttpRequest chargeRequest = HttpRequest.newBuilder(URI.create("https://payments.example.com/api/v1/charges"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(String.format(
                        "{\"orderId\":%d,\"customerId\":%d,\"amount\":%s}",
                        order.getId(), order.getCustomerId(), order.getAmount())))
                .build();
        HttpResponse<String> chargeResponse = httpClient.send(chargeRequest, HttpResponse.BodyHandlers.ofString());
        if (chargeResponse.statusCode() != 200) {
            throw new IllegalStateException("Payment failed: " + chargeResponse.statusCode());
        }

        order.setPaymentId(chargeResponse.body());
        order.setStatus("PAID");
        orderRepository.save(order);

        try {
            HttpRequest mailRequest = HttpRequest.newBuilder(URI.create("https://mail.example.com/api/v1/send"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(String.format(
                            "{\"to\":\"%s\",\"template\":\"order-confirmed\",\"orderId\":%d}",
                            order.getEmail(), order.getId())))
                    .build();
            httpClient.send(mailRequest, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Could not send mail for order {}", order.getId());
        }

        if (order.getQuantity() > 100) {
            throw new IllegalStateException("Bulk orders need manual approval");
        }

        return order.getId();
    }
}
