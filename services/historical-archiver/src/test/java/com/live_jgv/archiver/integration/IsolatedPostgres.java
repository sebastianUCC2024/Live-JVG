package com.live_jgv.archiver.integration;

import java.net.URI;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** No network call before explicit approvals and strict isolated endpoint checks. */
final class IsolatedPostgres {
    static DriverManagerDataSource approvedFreshDatabase() {
        var ds=configuredDataSource(System.getenv("DEV3_IT_MIGRATION_APPROVAL"),System.getenv("DEV3_IT_DB_URL"),
                System.getenv("DEV3_IT_DB_USERNAME"),System.getenv("DEV3_IT_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        Integer version=jdbc.queryForObject("SELECT current_setting('server_version_num')::integer",Integer.class);
        if(version==null || version<170000 || version>=180000)
            throw new IllegalStateException("Approved PostgreSQL 17 instance required");
        Long schemas=jdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname=?",Long.class,"archiver");
        if(schemas==null||schemas!=0)throw new IllegalStateException("Refuse existing service schema; use a NEW approved test database");
        return ds;
    }
    /** Validation and datasource construction only: deliberately opens no connection. */
    static DriverManagerDataSource configuredDataSource(String approval,String url,String user,String password) {
        if(!"I_APPROVE_ISOLATED_MIGRATIONS".equals(approval))
            throw new IllegalStateException("Separate migration approval required");
        if(url==null||!url.startsWith("jdbc:postgresql://"))throw new IllegalArgumentException("Explicit PostgreSQL URL required");
        var uri=URI.create(url.substring(5));
        if(!Set.of("localhost","127.0.0.1").contains(uri.getHost())||uri.getPort()<1024
                ||Set.of(5432,19534,55432,55433,55434).contains(uri.getPort())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||!uri.getPath().matches("/dev3_it_archiver_[A-Za-z0-9_]+")
                ||user==null||!user.startsWith("dev3_it_")||password==null||password.isBlank())
            throw new IllegalArgumentException("New isolated database, local non-MVP port and dedicated role required");
        return new DriverManagerDataSource(url,user,password);
    }

}
