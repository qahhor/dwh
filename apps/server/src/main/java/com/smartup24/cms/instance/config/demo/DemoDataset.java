package com.smartup24.cms.instance.config.demo;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The records {@link DemoData} adds (plan 10/10, item 6.5): fictional people on the reserved domain example.com, two
 * projects with their tasks, notes and orders. The first value of each record is its demo key, the one the seeder looks
 * the record up by.
 */
final class DemoDataset {

    static final List<Person> PEOPLE = List.of(
            new Person("demo.anna", "Анна Каримова (демо)", "demo.anna@example.com"),
            new Person("demo.bobur", "Бобур Алиев (демо)", "demo.bobur@example.com"),
            new Person("demo.dilnoza", "Дилноза Юсупова (демо)", "demo.dilnoza@example.com"));

    static final List<Project> PROJECTS = List.of(
            new Project(
                    "Демо: запуск склада",
                    "Учебный проект: приёмка товара, адресное хранение и инвентаризация.",
                    List.of(
                            new Task("Описать процесс приёмки", "high", 0),
                            new Task("Настроить справочник ячеек", "medium", 1),
                            new Task("Провести пробную инвентаризацию", "low", 2))),
            new Project(
                    "Демо: сайт компании",
                    "Учебный проект: структура разделов, тексты и запуск.",
                    List.of(
                            new Task("Согласовать структуру разделов", "critical", 1),
                            new Task("Подготовить тексты главной страницы", "medium", 2),
                            new Task("Проверить вёрстку на телефоне", "low", 0))));

    static final List<Map<String, Object>> NOTES = List.of(
            note(
                    "Демо: с чего начать",
                    "Откройте **Задачи** и **Проекты**: записи созданы профилем `demo`.",
                    "blue",
                    true),
            note("Демо: идеи для модуля", "Справочник, документ со строками, документ со статусами.", "green", false),
            note(
                    "Демо: вопросы к команде",
                    "- Кто владеет справочником ячеек?\n- Когда пробная инвентаризация?",
                    "yellow",
                    false));

    static final List<Order> ORDERS = List.of(
            new Order(
                    "Демо: ООО «Ромашка»",
                    "UZS",
                    true,
                    List.of(line("Бумага A4", "20", "45000"), line("Ручки", "50", "2500"))),
            new Order("Демо: ИП «Восход»", "USD", false, List.of(line("Сканер штрихкодов", "2", "120"))));

    private DemoDataset() {}

    record Person(String login, String name, String email) {
        Map<String, Object> values() {
            return Map.of("login", login, "name", name, "email", email);
        }
    }

    record Project(String name, String description, List<Task> tasks) {
        Map<String, Object> values() {
            return Map.of("name", name, "description", description);
        }
    }

    /** A task; {@code responsible} is the index of its person in {@link #PEOPLE}. */
    record Task(String title, String priority, int responsible) {
        Map<String, Object> values() {
            return Map.of("title", title, "priority", priority, "descriptionMarkdown", "Задача демо-данных.");
        }
    }

    record Order(String customer, String currency, boolean posted, List<Map<String, Object>> lines) {
        Map<String, Object> values() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("customer", customer);
            values.put("currency", currency);
            values.put("comment", "Заказ демо-данных.");
            values.put("lines", lines);
            return values;
        }
    }

    private static Map<String, Object> note(String title, String content, String color, boolean pinned) {
        return Map.of("title", title, "contentMd", content, "color", color, "isPinned", pinned);
    }

    private static Map<String, Object> line(String product, String qty, String price) {
        return Map.of("product", product, "qty", new BigDecimal(qty), "price", new BigDecimal(price));
    }
}
