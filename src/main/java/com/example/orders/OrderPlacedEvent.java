package com.example.orders;

import java.math.BigDecimal;

public record OrderPlacedEvent(long orderId, BigDecimal amount) {}
