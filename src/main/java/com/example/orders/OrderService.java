package com.example.orders;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String PAYMENT_URL = "https://payments.example.com/api/v1/charges";
    private static final String INVENTORY_URL = "https://inventory.example.com/api/v1/reservations";
    private static final String MAIL_URL = "https://mail.example.com/api/v1/send";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public OrderService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    public record PlaceOrderRequest(long customerId, String sku, int quantity, BigDecimal amount, String email) {}

    @Transactional
    public long placeOrder(PlaceOrderRequest request) throws IOException, InterruptedException, SQLException {
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO orders (customer_id, sku, quantity, amount, status) "
                        + "VALUES (?, ?, ?, ?, 'NEW') RETURNING id",
                Long.class,
                request.customerId(),
                request.sku(),
                request.quantity(),
                request.amount());

        reserveStock(orderId, request);

        String paymentId = chargeCustomer(orderId, request);

        Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO order_audit (order_id, event, payment_id) VALUES (?, 'PAID', ?)");
        statement.setLong(1, orderId);
        statement.setString(2, paymentId);
        statement.executeUpdate();

        jdbcTemplate.update("UPDATE orders SET status = 'PAID', payment_id = ? WHERE id = ?", paymentId, orderId);

        sendConfirmationMail(request.email(), orderId);

        if (request.quantity() > 100) {
            throw new IllegalStateException("Bulk orders need manual approval");
        }

        statement.close();
        connection.close();
        return orderId;
    }

    public void retryFailedOrders() throws Exception {
        List<Long> failedIds = jdbcTemplate.queryForList("SELECT id FROM orders WHERE status = 'NEW'", Long.class);
        for (Long id : failedIds) {
            PlaceOrderRequest request = jdbcTemplate.queryForObject(
                    "SELECT customer_id, sku, quantity, amount, email FROM orders WHERE id = ?",
                    (rs, rowNum) -> new PlaceOrderRequest(
                            rs.getLong("customer_id"),
                            rs.getString("sku"),
                            rs.getInt("quantity"),
                            rs.getBigDecimal("amount"),
                            rs.getString("email")),
                    id);
            try {
                placeOrder(request);
            } catch (Exception e) {
                log.warn("Retry for order {} failed", id, e);
            }
        }
    }

    private void reserveStock(long orderId, PlaceOrderRequest request) throws IOException, InterruptedException {
        String body = String.format(
                "{\"orderId\":%d,\"sku\":\"%s\",\"quantity\":%d}", orderId, request.sku(), request.quantity());
        HttpResponse<String> response = post(INVENTORY_URL, body);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Stock reservation failed: " + response.statusCode());
        }
    }

    private String chargeCustomer(long orderId, PlaceOrderRequest request) throws IOException, InterruptedException {
        String body = String.format(
                "{\"orderId\":%d,\"customerId\":%d,\"amount\":%s}", orderId, request.customerId(), request.amount());
        HttpResponse<String> response = post(PAYMENT_URL, body);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Payment failed: " + response.statusCode());
        }
        return response.body();
    }

    private void sendConfirmationMail(String email, long orderId) {
        try {
            post(MAIL_URL, String.format("{\"to\":\"%s\",\"template\":\"order-confirmed\",\"orderId\":%d}", email, orderId));
        } catch (Exception e) {
            log.warn("Could not send mail for order {}", orderId);
        }
    }

    private HttpResponse<String> post(String url, String body) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }
}
