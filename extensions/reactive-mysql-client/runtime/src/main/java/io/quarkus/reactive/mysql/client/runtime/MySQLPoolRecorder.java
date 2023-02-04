package io.quarkus.reactive.mysql.client.runtime;

import static io.quarkus.credentials.CredentialsProvider.PASSWORD_PROPERTY_NAME;
import static io.quarkus.credentials.CredentialsProvider.USER_PROPERTY_NAME;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configureJksKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configureJksTrustOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePemKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePemTrustOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePfxKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePfxTrustOptions;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Instance;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.datasource.runtime.DataSourceRuntimeConfig;
import io.quarkus.datasource.runtime.DataSourcesRuntimeConfig;
import io.quarkus.reactive.datasource.ReactiveDataSource;
import io.quarkus.reactive.datasource.runtime.DataSourceReactiveRuntimeConfig;
import io.quarkus.reactive.datasource.runtime.DataSourcesReactiveRuntimeConfig;
import io.quarkus.reactive.mysql.client.MySQLPoolCreator;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Vertx;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLPool;
import io.vertx.mysqlclient.SslMode;
import io.vertx.sqlclient.PoolOptions;

@Recorder
@SuppressWarnings("deprecation")
public class MySQLPoolRecorder {

    private static final Logger log = Logger.getLogger(MySQLPoolRecorder.class);

    public RuntimeValue<MySQLPool> configureMySQLPool(RuntimeValue<Vertx> vertx,
            Supplier<Integer> eventLoopCount,
            String dataSourceName,
            DataSourcesRuntimeConfig dataSourcesRuntimeConfig,
            DataSourcesReactiveRuntimeConfig dataSourcesReactiveRuntimeConfig,
            DataSourcesReactiveMySQLConfig dataSourcesReactiveMySQLConfig,
            ShutdownContext shutdown) {

        MySQLPool mysqlPool = initialize(vertx.getValue(),
                eventLoopCount.get(),
                dataSourceName,
                dataSourcesRuntimeConfig.getDataSourceRuntimeConfig(dataSourceName),
                dataSourcesReactiveRuntimeConfig.getDataSourceReactiveRuntimeConfig(dataSourceName),
                dataSourcesReactiveMySQLConfig.getDataSourceReactiveRuntimeConfig(dataSourceName));

        shutdown.addShutdownTask(mysqlPool::close);
        return new RuntimeValue<>(mysqlPool);
    }

    public RuntimeValue<io.vertx.mutiny.mysqlclient.MySQLPool> mutinyMySQLPool(RuntimeValue<MySQLPool> mysqlPool) {
        return new RuntimeValue<>(io.vertx.mutiny.mysqlclient.MySQLPool.newInstance(mysqlPool.getValue()));
    }

    private MySQLPool initialize(Vertx vertx,
            Integer eventLoopCount,
            String dataSourceName, DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMySQLConfig dataSourceReactiveMySQLConfig) {
        PoolOptions poolOptions = toPoolOptions(eventLoopCount, dataSourceRuntimeConfig, dataSourceReactiveRuntimeConfig,
                dataSourceReactiveMySQLConfig);
        MySQLConnectOptions mysqlConnectOptions = toMySQLConnectOptions(dataSourceRuntimeConfig,
                dataSourceReactiveRuntimeConfig, dataSourceReactiveMySQLConfig);

        // Use the convention defined by Quarkus Micrometer Vert.x metrics to create metrics prefixed with mysql.
        // and the client_name as tag.
        // See io.quarkus.micrometer.runtime.binder.vertx.VertxMeterBinderAdapter.extractPrefix and
        // io.quarkus.micrometer.runtime.binder.vertx.VertxMeterBinderAdapter.extractClientName
        mysqlConnectOptions.setMetricsName("mysql|" + dataSourceName);

        if (dataSourceReactiveRuntimeConfig.threadLocal.isPresent()) {
            log.warn(
                    "Configuration element 'thread-local' on Reactive datasource connections is deprecated and will be ignored. The started pool will always be based on a per-thread separate pool now.");
        }
        return createPool(vertx, poolOptions, mysqlConnectOptions, dataSourceName);
    }

    private PoolOptions toPoolOptions(Integer eventLoopCount,
            DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMySQLConfig dataSourceReactiveMySQLConfig) {
        PoolOptions poolOptions;
        poolOptions = new PoolOptions();

        poolOptions.setMaxSize(dataSourceReactiveRuntimeConfig.maxSize);

        if (dataSourceReactiveRuntimeConfig.idleTimeout.isPresent()) {
            int idleTimeout = Math.toIntExact(dataSourceReactiveRuntimeConfig.idleTimeout.get().toMillis());
            poolOptions.setIdleTimeout(idleTimeout).setIdleTimeoutUnit(TimeUnit.MILLISECONDS);
        }

        if (dataSourceReactiveRuntimeConfig.shared) {
            poolOptions.setShared(true);
            if (dataSourceReactiveRuntimeConfig.name.isPresent()) {
                poolOptions.setName(dataSourceReactiveRuntimeConfig.name.get());
            }
        }

        if (dataSourceReactiveRuntimeConfig.eventLoopSize.isPresent()) {
            poolOptions.setEventLoopSize(Math.max(0, dataSourceReactiveRuntimeConfig.eventLoopSize.getAsInt()));
        } else if (eventLoopCount != null) {
            poolOptions.setEventLoopSize(Math.max(0, eventLoopCount));
        }

        if (dataSourceReactiveMySQLConfig.connectionTimeout.isPresent()) {
            poolOptions.setConnectionTimeout(dataSourceReactiveMySQLConfig.connectionTimeout.getAsInt());
            poolOptions.setConnectionTimeoutUnit(TimeUnit.SECONDS);
        }

        return poolOptions;
    }

    private MySQLConnectOptions toMySQLConnectOptions(DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMySQLConfig dataSourceReactiveMySQLConfig) {
        MySQLConnectOptions mysqlConnectOptions;
        if (dataSourceReactiveRuntimeConfig.url.isPresent()) {
            String url = dataSourceReactiveRuntimeConfig.url.get();
            // clean up the URL to make migrations easier
            if (url.startsWith("vertx-reactive:mysql://")) {
                url = url.substring("vertx-reactive:".length());
            }
            mysqlConnectOptions = MySQLConnectOptions.fromUri(url);
        } else {
            mysqlConnectOptions = new MySQLConnectOptions();
        }

        if (dataSourceRuntimeConfig.username.isPresent()) {
            mysqlConnectOptions.setUser(dataSourceRuntimeConfig.username.get());
        }

        if (dataSourceRuntimeConfig.password.isPresent()) {
            mysqlConnectOptions.setPassword(dataSourceRuntimeConfig.password.get());
        }

        // credentials provider
        if (dataSourceRuntimeConfig.credentialsProvider.isPresent()) {
            String beanName = dataSourceRuntimeConfig.credentialsProviderName.orElse(null);
            CredentialsProvider credentialsProvider = CredentialsProviderFinder.find(beanName);
            String name = dataSourceRuntimeConfig.credentialsProvider.get();
            Map<String, String> credentials = credentialsProvider.getCredentials(name);
            String user = credentials.get(USER_PROPERTY_NAME);
            String password = credentials.get(PASSWORD_PROPERTY_NAME);
            if (user != null) {
                mysqlConnectOptions.setUser(user);
            }
            if (password != null) {
                mysqlConnectOptions.setPassword(password);
            }
        }

        if (dataSourceReactiveMySQLConfig.cachePreparedStatements.isPresent()) {
            log.warn(
                    "datasource.reactive.mysql.cache-prepared-statements is deprecated, use datasource.reactive.cache-prepared-statements instead");
            mysqlConnectOptions.setCachePreparedStatements(dataSourceReactiveMySQLConfig.cachePreparedStatements.get());
        } else {
            mysqlConnectOptions.setCachePreparedStatements(dataSourceReactiveRuntimeConfig.cachePreparedStatements);
        }

        if (dataSourceReactiveMySQLConfig.charset.isPresent()) {
            mysqlConnectOptions.setCharset(dataSourceReactiveMySQLConfig.charset.get());
        }
        if (dataSourceReactiveMySQLConfig.collation.isPresent()) {
            mysqlConnectOptions.setCollation(dataSourceReactiveMySQLConfig.collation.get());
        }

        if (dataSourceReactiveMySQLConfig.pipeliningLimit.isPresent()) {
            mysqlConnectOptions.setPipeliningLimit(dataSourceReactiveMySQLConfig.pipeliningLimit.getAsInt());
        }

        if (dataSourceReactiveMySQLConfig.useAffectedRows.isPresent()) {
            mysqlConnectOptions.setUseAffectedRows(dataSourceReactiveMySQLConfig.useAffectedRows.get());
        }

        if (dataSourceReactiveMySQLConfig.sslMode.isPresent()) {
            final SslMode sslMode = dataSourceReactiveMySQLConfig.sslMode.get();
            mysqlConnectOptions.setSslMode(sslMode);

            // If sslMode is verify-identity, we also need a hostname verification algorithm
            if (sslMode == SslMode.VERIFY_IDENTITY && (!dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm
                    .isPresent() || "".equals(dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm.get()))) {
                throw new IllegalArgumentException(
                        "quarkus.datasource.reactive.hostname-verification-algorithm must be specified under verify-identity sslmode");
            }
        }

        mysqlConnectOptions.setTrustAll(dataSourceReactiveRuntimeConfig.trustAll);

        configurePemTrustOptions(mysqlConnectOptions, dataSourceReactiveRuntimeConfig.trustCertificatePem);
        configureJksTrustOptions(mysqlConnectOptions, dataSourceReactiveRuntimeConfig.trustCertificateJks);
        configurePfxTrustOptions(mysqlConnectOptions, dataSourceReactiveRuntimeConfig.trustCertificatePfx);

        configurePemKeyCertOptions(mysqlConnectOptions, dataSourceReactiveRuntimeConfig.keyCertificatePem);
        configureJksKeyCertOptions(mysqlConnectOptions, dataSourceReactiveRuntimeConfig.keyCertificateJks);
        configurePfxKeyCertOptions(mysqlConnectOptions, dataSourceReactiveRuntimeConfig.keyCertificatePfx);

        mysqlConnectOptions.setReconnectAttempts(dataSourceReactiveRuntimeConfig.reconnectAttempts);

        mysqlConnectOptions.setReconnectInterval(dataSourceReactiveRuntimeConfig.reconnectInterval.toMillis());

        if (dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm.isPresent()) {
            mysqlConnectOptions.setHostnameVerificationAlgorithm(
                    dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm.get());
        }

        if (dataSourceReactiveMySQLConfig.authenticationPlugin.isPresent()) {
            mysqlConnectOptions.setAuthenticationPlugin(dataSourceReactiveMySQLConfig.authenticationPlugin.get());
        }

        dataSourceReactiveRuntimeConfig.additionalProperties.forEach(mysqlConnectOptions::addProperty);

        return mysqlConnectOptions;
    }

    private MySQLPool createPool(Vertx vertx, PoolOptions poolOptions, MySQLConnectOptions mySQLConnectOptions,
            String dataSourceName) {
        Instance<MySQLPoolCreator> instance;
        if (DataSourceUtil.isDefault(dataSourceName)) {
            instance = Arc.container().select(MySQLPoolCreator.class);
        } else {
            instance = Arc.container().select(MySQLPoolCreator.class,
                    new ReactiveDataSource.ReactiveDataSourceLiteral(dataSourceName));
        }
        if (instance.isResolvable()) {
            MySQLPoolCreator.Input input = new DefaultInput(vertx, poolOptions, mySQLConnectOptions);
            return instance.get().create(input);
        }
        return MySQLPool.pool(vertx, mySQLConnectOptions, poolOptions);
    }

    private static class DefaultInput implements MySQLPoolCreator.Input {
        private final Vertx vertx;
        private final PoolOptions poolOptions;
        private final MySQLConnectOptions mySQLConnectOptions;

        public DefaultInput(Vertx vertx, PoolOptions poolOptions, MySQLConnectOptions mySQLConnectOptions) {
            this.vertx = vertx;
            this.poolOptions = poolOptions;
            this.mySQLConnectOptions = mySQLConnectOptions;
        }

        @Override
        public Vertx vertx() {
            return vertx;
        }

        @Override
        public PoolOptions poolOptions() {
            return poolOptions;
        }

        @Override
        public MySQLConnectOptions mySQLConnectOptions() {
            return mySQLConnectOptions;
        }
    }
}
