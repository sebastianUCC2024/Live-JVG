package com.live_jgv.worker.integration;

import java.net.URI;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** No network call before explicit approvals and strict isolated endpoint checks. */
final class IsolatedPostgres {
    static DriverManagerDataSource approvedFreshDatabase() {
        if(!"I_APPROVE_ISOLATED_MIGRATIONS".equals(System.getenv("DEV3_IT_MIGRATION_APPROVAL")))
            throw new IllegalStateException("Separate migration approval required");
        String url=System.getenv("DEV3_IT_DB_URL"),user=System.getenv("DEV3_IT_DB_USERNAME"),password=System.getenv("DEV3_IT_DB_PASSWORD");
        if(url==null||!url.startsWith("jdbc:postgresql://"))throw new IllegalArgumentException("Explicit PostgreSQL URL required");
        var uri=URI.create(url.substring(5));
        if(!Set.of("localhost","127.0.0.1").contains(uri.getHost())||uri.getPort()<1024
                ||Set.of(5432,55432,55433,55434).contains(uri.getPort())||uri.getUserInfo()!=null||uri.getQuery()!=null
                ||!uri.getPath().matches("/dev3_it_betting_worker_[A-Za-z0-9_]+")
                ||user==null||!user.startsWith("dev3_it_")||password==null||password.isBlank())
            throw new IllegalArgumentException("New isolated database, local non-MVP port and dedicated role required");
        var ds=new DriverManagerDataSource(url,user,password);
        var jdbc=new JdbcTemplate(ds);
        Long schemas=jdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname=?",Long.class,"betting_worker");
        if(schemas==null||schemas!=0)throw new IllegalStateException("Refuse existing service schema; use a NEW approved test database");
        return ds;
    }
}
