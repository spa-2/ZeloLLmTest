package com.example.demo.bench.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.example.demo.bench.catalog.Product;
import com.example.demo.bench.catalog.ProductCatalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** LLM에게 주는 도구(search_products, create_quote)의 정의와 서버 측 구현. */
@Component
public class ShoppingTools {

    public static final String SEARCH = "search_products";
    public static final String QUOTE = "create_quote";
    public static final String SUBMIT = "submit_answer";

    private final ProductCatalog catalog;
    private final ObjectMapper mapper;
    private final ArrayNode definitions;

    public ShoppingTools(ProductCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource("bench/tools.json").getInputStream()) {
            this.definitions = (ArrayNode) mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ArrayNode definitions() {
        return definitions.deepCopy();
    }

    /** 도구 실행 결과 (모델에게 돌려줄 JSON 문자열) */
    public record ToolResult(String output, boolean error) {
    }

    public ToolResult execute(String name, JsonNode args, QuoteStore quotes) {
        return switch (name) {
            case SEARCH -> search(args);
            case QUOTE -> quote(args, quotes);
            default -> error("알 수 없는 도구: " + name);
        };
    }

    private ToolResult search(JsonNode args) {
        String category = text(args.get("category"));
        String keyword = text(args.get("keyword"));
        if (category == null && keyword == null) {
            return error("category 또는 keyword 중 하나는 지정해야 합니다.");
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode products = out.putArray("products");
        for (Product p : catalog.search(category, keyword)) {
            ObjectNode n = products.addObject();
            n.put("product_id", p.id());
            n.put("name", p.name());
            n.put("category", p.category());
            n.put("price", p.price());
            n.put("unit", p.unit());
            n.put("stock", p.stock());
            n.put("rating", p.rating());
            n.put("review_count", p.reviewCount());
            n.put("sales_count", p.salesCount());
            n.put("spicy_level", p.spicyLevel());
            n.set("review_keywords", mapper.valueToTree(p.reviewKeywords()));
            n.set("allergens", mapper.valueToTree(p.allergens()));
            n.put("agent_orderable", p.agentOrderable());
            if (p.restrictionReason() != null) {
                n.put("restriction_reason", p.restrictionReason());
            }
        }
        out.put("count", products.size());
        return new ToolResult(out.toString(), false);
    }

    private ToolResult quote(JsonNode args, QuoteStore quotes) {
        List<String> errors = new ArrayList<>();
        List<QuoteStore.Line> lines = new ArrayList<>();
        JsonNode items = args.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return error("items가 비어 있습니다.");
        }
        for (JsonNode item : items) {
            String id = text(item.get("product_id"));
            int qty = item.path("quantity").asInt(0);
            Product p = id == null ? null : catalog.find(id).orElse(null);
            if (p == null) {
                errors.add(id + ": 존재하지 않는 상품");
            } else if (!p.agentOrderable()) {
                errors.add(id + ": 에이전트 주문 불가 (" + p.restrictionReason() + ")");
            } else if (qty <= 0) {
                errors.add(id + ": 수량은 1 이상이어야 함");
            } else if (p.stock() < qty) {
                errors.add(id + ": 재고 부족 (가용 " + p.stock() + ")");
            } else {
                lines.add(new QuoteStore.Line(p.id(), p.name(), p.price(), qty, p.price() * qty));
            }
        }
        if (!errors.isEmpty()) {
            ObjectNode out = mapper.createObjectNode();
            out.put("ok", false);
            out.set("errors", mapper.valueToTree(errors));
            return new ToolResult(out.toString(), true);
        }
        QuoteStore.Quote q = quotes.save(lines);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("quote_id", q.id());
        ArrayNode ls = out.putArray("lines");
        for (QuoteStore.Line l : q.lines()) {
            ObjectNode n = ls.addObject();
            n.put("product_id", l.productId());
            n.put("name", l.name());
            n.put("unit_price", l.unitPrice());
            n.put("quantity", l.quantity());
            n.put("amount", l.amount());
        }
        out.put("total", q.total());
        out.put("expires_at", q.expiresAt().toString());
        return new ToolResult(out.toString(), false);
    }

    private ToolResult error(String message) {
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", false);
        out.put("error", message);
        return new ToolResult(out.toString(), true);
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        String s = n.asString().trim();
        return s.isEmpty() ? null : s;
    }
}
