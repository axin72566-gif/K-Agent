# Redis 在 Spring Boot 中的使用指南

## 添加依赖

在 pom.xml 中添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

## 配置文件

application.yml 配置：

```yaml
spring:
  data:
    redis:
      host: 192.168.60.133
      port: 6379
      timeout: 60000
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
```

## 常用操作

使用 StringRedisTemplate 进行 Redis 操作：

```java
@Autowired
private StringRedisTemplate redis;

// 写入
redis.opsForValue().set("key", "value", Duration.ofMinutes(30));

// 读取
String value = redis.opsForValue().get("key");

// 删除
redis.delete("key");

// Hash 操作
redis.opsForHash().putAll("hashKey", map);
Map<Object, Object> entries = redis.opsForHash().entries("hashKey");
```

## 连接池

使用 Lettuce 连接池需要添加 commons-pool2 依赖：

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```
