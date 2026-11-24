package com.mediastudio.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostSyncTest {

    @Test
    void parsesNeonConnectionString() {
        PostSync.Conn c = PostSync.parse(
                "postgresql://neondb_owner:npg_a%40b@ep-cool-123-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
        assertEquals("jdbc:postgresql://ep-cool-123-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channelBinding=require", c.jdbcUrl());
        assertEquals("neondb_owner", c.user());
        assertEquals("npg_a@b", c.password());
        assertEquals("ep-cool-123-pooler.ap-southeast-1.aws.neon.tech/neondb", c.label());
    }

    @Test
    void addsSslForRemoteHostsOnly() {
        assertEquals("jdbc:postgresql://db.abc.supabase.co:5432/postgres?sslmode=require",
                PostSync.parse("postgres://postgres:pw@db.abc.supabase.co:5432/postgres").jdbcUrl());
        assertEquals("jdbc:postgresql://localhost:55432/postgres",
                PostSync.parse("postgresql://tester@localhost:55432/postgres").jdbcUrl());
        assertEquals("jdbc:postgresql://localhost/postgres", PostSync.parse("jdbc:postgresql://localhost/postgres").jdbcUrl());
    }

    @Test
    void rejectsSomethingElse() {
        assertThrows(IllegalArgumentException.class, () -> PostSync.parse("mysql://x@y/z"));
    }

    @Test
    void similarTopics() {
        assertTrue(PostSync.similar("Trận Bạch Đằng năm 938", "Ngô Quyền và trận Bạch Đằng năm 938"));
        assertFalse(PostSync.similar("Trận Bạch Đằng năm 938", "Trận Bạch Đằng năm 1288"));
        assertTrue(PostSync.similar("Thánh Gióng", "Sự tích Thánh Gióng"));
        assertTrue(PostSync.similar("quang trung dai pha quan thanh", "Quang Trung đại phá quân Thanh 1789"));
        assertFalse(PostSync.similar("Hà Nội", "Hoàng Diệu giữ thành Hà Nội năm 1882"));
        assertFalse(PostSync.similar("Hai Bà Trưng khởi nghĩa", "Khởi nghĩa Lam Sơn"));
    }
}
