package dev.notch.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Known tools, their aliases and their category. Lets Notch see that "Mongo", "MongoDB" and
 * "mongoose" are one choice, and that it competes with PostgreSQL as a database.
 */
public final class TechCatalog {

    /** A known tool: canonical display name and category. */
    public record Tech(String name, String category) {
    }

    private static final Map<String, Tech> BY_ALIAS = new LinkedHashMap<>();
    private static final Pattern ALIAS_PATTERN;

    static {
        add("database", "PostgreSQL", "postgresql", "postgres", "pg", "psql", "psycopg2", "psycopg", "pg-promise", "postgres.js");
        add("database", "MongoDB", "mongodb", "mongo", "mongoose", "pymongo", "mongodb-driver-sync", "spring-boot-starter-data-mongodb", "motor");
        add("database", "MySQL", "mysql", "mariadb", "mysql2", "pymysql", "mysql-connector-java", "mysql-connector-j");
        add("database", "SQLite", "sqlite", "sqlite3", "better-sqlite3");
        add("database", "DynamoDB", "dynamodb", "dynamo");
        add("database", "Firebase", "firebase", "firestore");
        add("database", "Supabase", "supabase");
        add("cache", "Redis", "redis", "ioredis", "jedis", "lettuce-core");
        add("cache", "Memcached", "memcached", "memcache");
        add("auth", "JWT", "jwt", "jwts", "json web token", "json web tokens", "jsonwebtoken", "jjwt", "pyjwt");
        add("auth", "Server-side sessions", "server-side sessions", "server side sessions", "sessions", "session cookies", "express-session");
        add("auth", "OAuth", "oauth", "oauth2");
        add("auth", "Auth0", "auth0");
        add("auth", "Clerk", "clerk");
        add("queue", "Kafka", "kafka", "kafkajs");
        // A product family member is its own choice: ruling out Redis Streams for events says
        // nothing about Redis as a cache. Longest-alias-first matching keeps them apart.
        add("queue", "Redis Streams", "redis streams", "redis stream");
        add("queue", "RabbitMQ", "rabbitmq");
        add("queue", "SQS", "sqs");
        add("api", "GraphQL", "graphql", "apollo", "apollo-server", "graphql-yoga");
        add("api", "REST", "rest api", "rest");
        add("api", "gRPC", "grpc");
        add("frontend", "React", "react");
        add("frontend", "Vue", "vue", "vue.js", "nuxt");
        add("frontend", "Svelte", "svelte", "sveltekit");
        add("frontend", "Angular", "angular");
        add("orm", "Prisma", "prisma");
        add("orm", "Drizzle", "drizzle");
        add("orm", "Hibernate", "hibernate", "jpa");
        add("orm", "Raw SQL", "raw sql");
        add("hosting", "Vercel", "vercel");
        add("hosting", "Netlify", "netlify");
        add("hosting", "Cloud Run", "cloud run");
        add("hosting", "Heroku", "heroku");
        add("payments", "Stripe", "stripe");
        add("payments", "PayPal", "paypal");
        add("payments", "Razorpay", "razorpay");
        add("testing", "Jest", "jest");
        add("testing", "Vitest", "vitest");
        add("styling", "Tailwind", "tailwind", "tailwindcss");
        add("ratelimit", "Token bucket", "token bucket");
        add("ratelimit", "In-memory limiter", "in-memory limiter", "in memory limiter");

        List<String> aliases = new ArrayList<>(BY_ALIAS.keySet());
        aliases.sort((a, b) -> b.length() - a.length());  // longest first: "server-side sessions" before "sessions"
        StringBuilder sb = new StringBuilder("(?<![\\w-])(?:");
        for (int i = 0; i < aliases.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(aliases.get(i)));
        }
        ALIAS_PATTERN = Pattern.compile(sb.append(")(?![\\w-])").toString(), Pattern.CASE_INSENSITIVE);
    }

    private TechCatalog() {
    }

    private static void add(String category, String name, String... aliases) {
        Tech t = new Tech(name, category);
        for (String a : aliases) BY_ALIAS.put(a.toLowerCase(Locale.ROOT), t);
    }

    /** Every known tool mentioned in {@code text}, in order, without repeats. */
    public static List<Tech> find(String text) {
        List<Tech> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = ALIAS_PATTERN.matcher(text);
        while (m.find()) {
            Tech t = BY_ALIAS.get(m.group().toLowerCase(Locale.ROOT));
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Every known tool in a category, such as all databases. */
    public static List<Tech> inCategory(String category) {
        List<Tech> out = new ArrayList<>();
        for (Tech t : BY_ALIAS.values()) if (t.category().equals(category) && !out.contains(t)) out.add(t);
        return out;
    }

    /** Aliases of a tool, for searching text that mentions it. */
    public static List<String> aliases(Tech tech) {
        List<String> out = new ArrayList<>();
        BY_ALIAS.forEach((alias, t) -> {
            if (t.equals(tech)) out.add(alias);
        });
        return out;
    }
}
