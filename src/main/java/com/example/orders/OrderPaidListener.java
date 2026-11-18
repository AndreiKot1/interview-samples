package com.example.orders;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderPaidListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidListener.class);

    private final OrderRepository orderRepository;
    private final ApiClient apiClient;
    private final PdfGenerator pdfGenerator;

    public OrderPaidListener(OrderRepository orderRepository, ApiClient apiClient, PdfGenerator pdfGenerator) {
        this.orderRepository = orderRepository;
        this.apiClient = apiClient;
        this.pdfGenerator = pdfGenerator;
    }

    @RabbitListener(queues = "order.placed")
    @Transactional
    public void onOrderPlaced(OrderPlacedEvent event) throws IOException, InterruptedException {
        log.info("Received order paid event for order {}", event.orderId());

        OrderEntity order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + event.orderId()));

        String shipmentId = apiClient.createShipment(order);
        apiClient.creditLoyaltyPoints(order);

        pdfGenerator.generateConfirmation(order, shipmentId);

        order.setStatus("SHIPPED");
        orderRepository.save(order);

        log.info("Order {} shipped with shipment {}", order.getId(), shipmentId);
    }
}
