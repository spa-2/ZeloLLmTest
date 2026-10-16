package com.example.demo.bench.catalog;

import java.util.List;

public record Product(
        String id,
        String name,
        String category,
        int price,
        String unit,
        int stock,
        double rating,
        int reviewCount,
        int salesCount,
        int spicyLevel,
        List<String> tags,
        List<String> reviewKeywords,
        List<String> allergens,
        boolean agentOrderable,
        String restrictionReason) {

    public Product {
        tags = tags == null ? List.of() : tags;
        reviewKeywords = reviewKeywords == null ? List.of() : reviewKeywords;
        allergens = allergens == null ? List.of() : allergens;
    }

    public boolean soldOut() {
        return stock <= 0;
    }
}
