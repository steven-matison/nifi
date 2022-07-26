/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.service;


// old imports
//import com.datastax.driver.core.Cluster;
//import com.datastax.driver.core.ConsistencyLevel;
//import com.datastax.driver.core.JdkSSLOptions;
//import com.datastax.driver.core.Metadata;
//import com.datastax.driver.core.ProtocolOptions;
//import com.datastax.driver.core.Session;
//import com.datastax.driver.core.SocketOptions;

// new imports
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.config.OptionsMap;
import com.datastax.oss.driver.api.core.config.TypedDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;

// i do not think ConsistencyLevel is needed in session ??
//import com.datastax.oss.driver.api.core.ConsistencyLevel;

//JdkSSLOptions not needed able to provide SSLContext directly to CqlSession
// ssl import moved here:
//import com.datastax.oss.driver.api.core.ssl.SslEngineFactory;

// unsure of import paths or acceptable 4.x usage for 
////  i believe these are now just configurable options in CqlSession ??

//ProtocolOptions
//SocketOptions

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.cassandra.CassandraSessionProviderService;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.security.util.ClientAuth;
import org.apache.nifi.ssl.SSLContextService;

@Tags({"cassandra", "dbcp", "database", "connection", "pooling"})
@CapabilityDescription("Provides connection session for Cassandra processors to work with Apache Cassandra.")
public class CassandraSessionProvider extends AbstractControllerService implements CassandraSessionProviderService {

    public static final int DEFAULT_CASSANDRA_PORT = 9042;

    // Common descriptors
    public static final PropertyDescriptor CONTACT_POINTS = new PropertyDescriptor.Builder()
            .name("Cassandra Contact Points")
            .description("Contact points are addresses of Cassandra nodes. The list of contact points should be "
                    + "comma-separated and in hostname:port format. Example node1:port,node2:port,...."
                    + " The default client port for Cassandra is 9042, but the port(s) must be explicitly specified.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.HOSTNAME_PORT_LIST_VALIDATOR)
            .build();

    public static final PropertyDescriptor KEYSPACE = new PropertyDescriptor.Builder()
            .name("Keyspace")
            .description("The Cassandra Keyspace to connect to. If no keyspace is specified, the query will need to " +
                    "include the keyspace name before any table reference, in case of 'query' native processors or " +
                    "if the processor supports the 'Table' property, the keyspace name has to be provided with the " +
                    "table name in the form of <KEYSPACE>.<TABLE>")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("The SSL Context Service used to provide client certificate information for TLS/SSL "
                    + "connections.")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();

    public static final PropertyDescriptor CLIENT_AUTH = new PropertyDescriptor.Builder()
            .name("Client Auth")
            .description("Client authentication policy when connecting to secure (TLS/SSL) cluster. "
                    + "Possible values are REQUIRED, WANT, NONE. This property is only used when an SSL Context "
                    + "has been defined and enabled.")
            .required(false)
            .allowableValues(ClientAuth.values())
            .defaultValue("REQUIRED")
            .build();

    public static final PropertyDescriptor USERNAME = new PropertyDescriptor.Builder()
            .name("Username")
            .description("Username to access the Cassandra cluster")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PASSWORD = new PropertyDescriptor.Builder()
            .name("Password")
            .description("Password to access the Cassandra cluster")
            .required(false)
            .sensitive(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor CONSISTENCY_LEVEL = new PropertyDescriptor.Builder()
            .name("Consistency Level")
            .description("The strategy for how many replicas must respond before results are returned.")
            .required(true)
            //.allowableValues(ConsistencyLevel.values())
            .defaultValue("ONE")
            .build();

    static final PropertyDescriptor COMPRESSION_TYPE = new PropertyDescriptor.Builder()
            .name("Compression Type")
            .description("Enable compression at transport-level requests and responses")
            .required(false)
            //.allowableValues(ProtocolOptions.Compression.values())
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue("NONE")
            .build();

    static final PropertyDescriptor READ_TIMEOUT_MS = new PropertyDescriptor.Builder()
        .name("read-timeout-ms")
        .displayName("Read Timout (ms)")
        .description("Read timeout (in milliseconds). 0 means no timeout. If no value is set, the underlying default will be used.")
        .required(false)
        .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
        .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
        .build();

    static final PropertyDescriptor CONNECT_TIMEOUT_MS = new PropertyDescriptor.Builder()
        .name("connect-timeout-ms")
        .displayName("Connect Timout (ms)")
        .description("Connection timeout (in milliseconds). 0 means no timeout. If no value is set, the underlying default will be used.")
        .required(false)
        .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
        .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
        .build();

    private List<PropertyDescriptor> properties;
    private CqlSession cluster;
    private CqlSession cassandraSession;

    @Override
    public void init(final ControllerServiceInitializationContext context) {
        List<PropertyDescriptor> props = new ArrayList<>();

        props.add(CONTACT_POINTS);
        props.add(CLIENT_AUTH);
        props.add(CONSISTENCY_LEVEL);
        props.add(COMPRESSION_TYPE);
        props.add(KEYSPACE);
        props.add(USERNAME);
        props.add(PASSWORD);
        props.add(PROP_SSL_CONTEXT_SERVICE);
        props.add(READ_TIMEOUT_MS);
        props.add(CONNECT_TIMEOUT_MS);

        properties = props;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) {
        connectToCassandra(context);
    }

    @OnDisabled
    public void onDisabled(){
        if (cassandraSession != null) {
            cassandraSession.close();
            cassandraSession = null;
        }
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
    }

    // comment this
    // I am not sure if we still need to override and "cluster details"
    // since this doesnt exist in [] i believe its not needed here
    //// can we get the same details from getMetadata ?? are they needed?
    //@Override
    // public CqlSession getCluster() {
    //    if (cluster != null) {
    //        return cluster;
    //    } else {
    //        throw new ProcessException("Unable to get the Cassandra cluster detail.");
    //    }
    //}

    @Override
    public CqlSession getCassandraSession() {
        if (cassandraSession != null) {
            return cassandraSession;
        } else {
            throw new ProcessException("Unable to get the Cassandra session.");
        }
    }

    private void connectToCassandra(ConfigurationContext context) {
        if (cluster == null) {
            ComponentLog log = getLogger();
            final String contactPointList = context.getProperty(CONTACT_POINTS).evaluateAttributeExpressions().getValue();
            final String keySpace = context.getProperty(KEYSPACE).getValue();
            final String consistencyLevel = context.getProperty(CONSISTENCY_LEVEL).getValue();
            final String compressionType = context.getProperty(COMPRESSION_TYPE).getValue();

            List<InetSocketAddress> contactPoints = getContactPoints(contactPointList);

            // Set up the client for secure (SSL/TLS communications) if configured to do so
            final SSLContextService sslService =
                    context.getProperty(PROP_SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
            final SSLContext sslContext;

            if (sslService == null) {
                sslContext = null;
            } else {
                sslContext = sslService.createContext();;
            }

            final String username, password;
            PropertyValue usernameProperty = context.getProperty(USERNAME).evaluateAttributeExpressions();
            PropertyValue passwordProperty = context.getProperty(PASSWORD).evaluateAttributeExpressions();

            if (usernameProperty != null && passwordProperty != null) {
                username = usernameProperty.getValue();
                password = passwordProperty.getValue();
            } else {
                username = null;
                password = null;
            }

            PropertyValue readTimeoutMillisProperty = context.getProperty(READ_TIMEOUT_MS).evaluateAttributeExpressions();
            Optional<Integer> readTimeoutMillisOptional = Optional.ofNullable(readTimeoutMillisProperty)
                .filter(PropertyValue::isSet)
                .map(PropertyValue::asInteger);

            PropertyValue connectTimeoutMillisProperty = context.getProperty(CONNECT_TIMEOUT_MS).evaluateAttributeExpressions();
            Optional<Integer> connectTimeoutMillisOptional = Optional.ofNullable(connectTimeoutMillisProperty)
                .filter(PropertyValue::isSet)
                .map(PropertyValue::asInteger);



            // the create function exists in following locations:
            //     nifi-cassandra-processors/src/main/java/org/apache/nifi/processors/cassandra/AbstractCassandraProcessor.java
            //     nifi-cassandra-processors/src/test/java/org/apache/nifi/processors/cassandra/AbstractCassandraProcessorTest.java
            //     nifi-cassandra-processors/src/test/java/org/apache/nifi/processors/cassandra/PutCassandraQLTest.java
            //     nifi-cassandra-bundle/nifi-cassandra-processors/src/test/java/org/apache/nifi/processors/cassandra/PutCassandraRecordTest.java
            //     nifi-cassandra-bundle/nifi-cassandra-processors/src/test/java/org/apache/nifi/processors/cassandra/QueryCassandraTest.java

            // new final 4.x function will need exist in all locations

            // Create the cluster and connect to it
            CqlSession newCluster = createCluster(contactPoints, keySpace, sslContext, username, password, compressionType, readTimeoutMillisOptional, connectTimeoutMillisOptional, consistencyLevel);

            final CqlSession newSession;
            newSession = newCluster;
            // need to work on consistency level
            // new 4.x shows executing consistency level with the query statement, not the connection
            // not sure if we can still set it per connection.
            //newCluster.getConfiguration().getQueryOptions().setConsistencyLevel(ConsistencyLevel.valueOf(consistencyLevel));
            // i think this can go to options map as REQUEST_CONSISTENCY
            Metadata metadata = newCluster.getMetadata();
            log.info("Connected to Cassandra cluster: {}", new Object[]{metadata.getClusterName()});

            cluster = newCluster;
            cassandraSession = newSession;
        }
    }

    private List<InetSocketAddress> getContactPoints(String contactPointList) {

        if (contactPointList == null) {
            return null;
        }

        final String[] contactPointStringList = contactPointList.split(",");
        List<InetSocketAddress> contactPoints = new ArrayList<>();

        for (String contactPointEntry : contactPointStringList) {
            String[] addresses = contactPointEntry.split(":");
            final String hostName = addresses[0].trim();
            final int port = (addresses.length > 1) ? Integer.parseInt(addresses[1].trim()) : DEFAULT_CASSANDRA_PORT;

            contactPoints.add(new InetSocketAddress(hostName, port));
        }

        return contactPoints;
    }

    private CqlSession createCluster(List<InetSocketAddress> contactPoints, String keyspace, SSLContext sslContext,
                                  String username, String password, String compressionType,
                                  Optional<Integer> readTimeoutMillisOptional, Optional<Integer> connectTimeoutMillisOptional, String consistencyLevel) {

        CqlSessionBuilder builder = CqlSession.builder().addContactPoint((InetSocketAddress) contactPoints);
        builder = builder.withKeyspace(keyspace);

        if (sslContext != null) {
            builder = builder.withSslContext(sslContext);
        }
        // this needs work, including upgrades for secure bundle / auth
        if (username != null && password != null) {
            builder = builder.withCredentials(username, password);
        }

        
        //if(ProtocolOptions.Compression.SNAPPY.equals(compressionType)) {
        //    builder = builder.withCompression(ProtocolOptions.Compression.SNAPPY);
        //} else if(ProtocolOptions.Compression.LZ4.equals(compressionType)) {
        //    builder = builder.withCompression(ProtocolOptions.Compression.LZ4);
        //}

        // testing config per: https://docs.datastax.com/en/drivers/java/4.14/com/datastax/oss/driver/api/core/config/DriverConfigLoader.html#fromMap-com.datastax.oss.driver.api.core.config.OptionsMap-

        // TypedDriverOption
        // https://docs.datastax.com/en/drivers/java/4.7/com/datastax/oss/driver/api/core/config/TypedDriverOption.html

        // This creates a configuration equivalent to the built-in reference.conf:
        OptionsMap map = OptionsMap.driverDefaults();

        // Customize an option:
        // example
        //map.put(TypedDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(5)); 

        // test consistency level
        map.put(TypedDriverOption.REQUEST_CONSISTENCY, consistencyLevel);

        //SocketOptions socketOptions = new SocketOptions();
        //readTimeoutMillisOptional.ifPresent(socketOptions::setReadTimeoutMillis);
        // trying to convert to options map
        ////map.put(TypedDriverOption.REQUEST_TIMEOUT, readTimeoutMillisOptional);
        //connectTimeoutMillisOptional.ifPresent(socketOptions::setConnectTimeoutMillis);
        // trying to convert to options map
        ////map.put(TypedDriverOption.CONNECTION_CONNECT_TIMEOUT,connectTimeoutMillisOptional);
        //builder.withSocketOptions(socketOptions);

        DriverConfigLoader loader = DriverConfigLoader.fromMap(map);
        builder = builder.withConfigLoader(loader);
            

        return builder.build();
    }
}
