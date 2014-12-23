/*
 * Copyright (c) 2014 Tim Boudreau
 *
 * This file is part of Scamper.
 *
 * Scamper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.mastfrog.scamper;

import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.giulius.DependenciesBuilder;
import com.mastfrog.guicy.annotations.Namespace;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.util.Checks;
import com.mastfrog.util.ConfigurationError;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.sctp.SctpChannelOption;
import io.netty.channel.sctp.nio.NioSctpChannel;
import io.netty.channel.sctp.nio.NioSctpServerChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Builder for SCTP servers and clients. To use, simply configure the settings
 * you want and use one of the <code>build()</code> methods to build what you
 * need. The {@link Control} object you get back from those methods can be used
 * to cleanly shut down and unload the server/client/sender, such that all
 * related objects <i>should</i> be able to be garbage collected.
 *
 * @author Tim Boudreau
 */
public class SctpServerAndClientBuilder {

    private int port = 8007;
    private String host = "127.0.0.1";
    private final Set<OptionEntry<?>> options = new LinkedHashSet<>();
    private final Set<OptionEntry<?>> serverOptions = new LinkedHashSet<>();
    private final Set<OptionEntry<?>> clientOptions = new LinkedHashSet<>();
    private int eventThreads = 1;
    private int workerThreads = -1;
    private final List<ProtocolModule.Entry> bindings = new LinkedList<>();
    private final List<Module> modules = new LinkedList<>();
    private final List<Settings> settings = new LinkedList<>();
    private boolean built;
    private final String settingsName;
    private boolean useBson = true;
    private ErrorHandler errors;

    public SctpServerAndClientBuilder() {
        this("scamper");
    }

    /**
     * Create a new builder.
     *
     * @param settingsName Settings (port and other configuration that can be
     * found using Guice's &#064;Named annotation) are looked up by merging
     * properties files named $settingsName.properties in /etc /opt/local/etc,
     * ~/ and ./ if they are present. If command-line arguments are passed to
     * the build methods, these can be overridden there
     */
    public SctpServerAndClientBuilder(String settingsName) {
        Checks.notNull("settingsName", settingsName);
        this.settingsName = settingsName;
        option(ChannelOption.TCP_NODELAY, true);
    }

    /**
     * Set the host - only useful if you're building a client. If not set, the
     * default of <code>127.0.0.1</code> will be used.
     *
     * @param host The host
     * @return This
     */
    public SctpServerAndClientBuilder withHost(String host) {
        Checks.notNull("host", host);
        this.host = host;
        return this;
    }

    /**
     * Turn on use of BSON in message payloads - this is the default, so you do
     * not need to call this unless you think some code has previously turned
     * this value off.
     *
     * @return This
     */
    public SctpServerAndClientBuilder useBson() {
        useBson = true;
        return this;
    }

    /**
     * Turn off use of BSON for debugging purposes - instead payloads will be
     * sent in plain JSON. Note that both sides of the wire will need to have
     * this set, or they will not succeed in sending each other messages. This
     * option is avialable for protocol debugging purposes.
     *
     * @return this
     */
    public SctpServerAndClientBuilder useJson() {
        useBson = false;
        return this;
    }

    private void checkBuilt() {
        if (built) {
            throw new ConfigurationError("build method already called");
        }
        built = true;
    }

    private Dependencies buildInjector(boolean forServer, String... cmdlineArgs) throws IOException {
        checkBuilt();
        Settings settings = settings(cmdlineArgs);
        DependenciesBuilder builder = deps(settings);
        Dependencies deps = builder.build();
        return deps;
    }

    /**
     * Build an sctp server
     *
     * @param cmdlineArgs Command-line arguments in the form
     * <code>--name value</code> to parse
     * @return A control object which can be used to get the server or shut it
     * down
     * @throws IOException if something goes wrong
     */
    public Control<SctpServer> buildServer(String... cmdlineArgs) throws IOException {
        Dependencies deps = buildInjector(true, cmdlineArgs);
        return new ControlImpl<SctpServer>(deps.getInstance(SctpServer.class), deps);
    }

    /**
     * Build an sctp client
     *
     * @param cmdlineArgs Command-line arguments in the form
     * <code>--name value</code> to parse
     * @return A control object which can be used to get the client or shut it
     * down
     * @throws IOException if something goes wrong
     */
    public Control<SctpClient> buildClient(String... cmdlineArgs) throws IOException {
        Dependencies deps = buildInjector(false, cmdlineArgs);
        return new ControlImpl<SctpClient>(deps.getInstance(SctpClient.class), deps);
    }

    /**
     * Build an object which can send messages to an sctp server
     *
     * @param cmdlineArgs Command-line arguments in the form
     * <code>--name value</code> to parse
     * @return A control object which can be used to get the server or shut it
     * down
     * @throws IOException if something goes wrong
     */
    public Control<Sender> buildSender(String... cmdlineArgs) throws IOException {
        Dependencies deps = buildInjector(false, cmdlineArgs);
        return new ControlImpl<Sender>(deps.getInstance(Sender.class), deps);
    }

    private ProtocolModule protoModule() {
        ProtocolModule m = new ProtocolModule(eventThreads, workerThreads, useBson);
        for (ProtocolModule.Entry e : this.bindings) {
            m.addEntry(e);
        }
        return m;
    }

    private DependenciesBuilder deps(Settings settings) {
        DependenciesBuilder builder = Dependencies.builder().add(settings, Namespace.DEFAULT);
        for (Module m : this.modules) {
            builder.add(m);
        }
        builder.add(protoModule());
        builder.add(new Mod(options, serverOptions, clientOptions, errors));
        return builder;
    }

    /**
     * Set the error handler that will receive uncaught exceptions in
     * message processing.  If not set, the default implementation simply
     * prints a stack trace and closes the channel (which may cause the
     * application to exit).
     * 
     * @param errors The error handler
     * @return This
     */
    public SctpServerAndClientBuilder withErrorHandler(ErrorHandler errors) {
        this.errors = errors;
        return this;
    }

    private static class ControlImpl<T> implements Control<T> {

        private final T object;
        private final Dependencies deps;

        public ControlImpl(T object, Dependencies deps) {
            this.object = object;
            this.deps = deps;
        }

        @Override
        public void shutdown() {
            deps.shutdown();
        }

        @Override
        public T get() {
            return object;
        }

        @Override
        public Dependencies getInjector() {
            return deps;
        }
    }

    static final class Mod extends AbstractModule {

        private final Set<OptionEntry<?>> bothOptions;
        private final Set<OptionEntry<?>> serverOptions;
        private final Set<OptionEntry<?>> clientOptions;
        private final ErrorHandler errors;

        public Mod(Set<OptionEntry<?>> entries, Set<OptionEntry<?>> serverOptions, Set<OptionEntry<?>> clientOptions, ErrorHandler errors) {
            this.bothOptions = entries;
            this.serverOptions = serverOptions;
            this.clientOptions = clientOptions;
            this.errors = errors;
        }

        @Override
        protected void configure() {
            TypeLiteral<Set<OptionEntry<?>>> e = new TypeLiteral<Set<OptionEntry<?>>>() {
            };
            bind(e).annotatedWith(Names.named("both")).toInstance(bothOptions);
            bind(e).annotatedWith(Names.named("server")).toInstance(clientOptions);
            bind(e).annotatedWith(Names.named("client")).toInstance(serverOptions);
            bind(ChannelConfigurer.class).to(Config.class);
            if (errors != null) {
                bind(ErrorHandler.class).toInstance(errors);
            }
        }

        static final class Config extends ChannelConfigurer {

            private final Set<OptionEntry<?>> bothOptions;
            private final Set<OptionEntry<?>> serverOptions;
            private final Set<OptionEntry<?>> clientOptions;

            @Inject
            public Config(@Named(value = "boss") EventLoopGroup boss,
                    @Named("worker") EventLoopGroup worker,
                    Provider<ChannelHandlerAdapter> handler,
                    @Named("both") Set<OptionEntry<?>> bothOptions,
                    @Named("server") Set<OptionEntry<?>> severOptions,
                    @Named("client") Set<OptionEntry<?>> clientOptions
            ) {
                super(boss, worker, handler);
                this.bothOptions = ImmutableSet.copyOf(bothOptions);
                this.serverOptions = ImmutableSet.copyOf(severOptions);
                this.clientOptions = ImmutableSet.copyOf(clientOptions);
            }

            public ServerBootstrap init(ServerBootstrap b) {
                Init init = new Init(handler);
                b = b.group(group, worker)
                        .channel(NioSctpServerChannel.class)
                        .option(ChannelOption.SO_BACKLOG, 1000);
                for (OptionEntry<?> entry : bothOptions) {
                    b = entry.apply(b);
                }
                for (OptionEntry<?> entry : serverOptions) {
                    b = entry.apply(b);
                }
                b.handler(new LoggingHandler(LogLevel.INFO))
                        .childHandler(init);
                return b;
            }

            public Bootstrap init(Bootstrap b) {
                Init init = new Init(handler);
                b = b.group(group).channel(NioSctpChannel.class)
                        .option(SctpChannelOption.SCTP_NODELAY, true);
                for (OptionEntry<?> entry : bothOptions) {
                    b = entry.apply(b);
                }
                for (OptionEntry<?> entry : clientOptions) {
                    b = entry.apply(b);
                }
                return b.handler(init);
            }

        }

    }

    private Settings settings(String... cmdlineArgs) throws IOException {
        SettingsBuilder b = new SettingsBuilder(settingsName)
                .add("port", "" + port)
                .add("host", host)
                .addDefaultLocations();
        for (Settings s : this.settings) {
            b.add(s);
        }
        b.parseCommandLineArguments(cmdlineArgs);
        return b.build();
    }

    private boolean hasOption(ChannelOption<?> opt) {
        for (OptionEntry<?> e : this.options) {
            if (e.option.equals(opt)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Map a message type to a handler which will receive messages of that type
     *
     * @param type The type
     * @param handlerType The handler type (will be instantiated by Guice)
     * @return this
     */
    public SctpServerAndClientBuilder bind(MessageType type, Class<? extends MessageHandler<?, ?>> handlerType) {
        Checks.notNull("type", type);
        Checks.notNull("handlerType", handlerType);
        if (!MessageHandler.class.isAssignableFrom(handlerType)) {
            throw new ClassCastException("Not a subclass of MessageHandler: " + handlerType);
        }
        for (ProtocolModule.Entry entry : bindings) {
            if (entry.type.equals(type)) {
                throw new ConfigurationError(entry.type + " was already "
                        + "registered for " + type);
            }
        }
        bindings.add(new ProtocolModule.Entry(type, handlerType));
        return this;
    }

    /**
     * Set a channel option for the NioSctpChannel to be used on all
     * client and server connections.
     *
     * @param <T> The option type
     * @param option The option
     * @param value The value to set the option to
     * @return this
     */
    public <T> SctpServerAndClientBuilder option(ChannelOption<T> option, T value) {
        Checks.notNull("option", option);
        Checks.notNull("value", value);
        OptionEntry<T> entry = new OptionEntry<>(option, value);
        options.remove(entry);
        options.add(entry);
        return this;
    }

    /**
     * Set a channel option for NioSctpChannels created for server connections
     * (Netty's ServerBootstrap) but not to be used for client connections. Use
     * in the case you will create a server that will also make client
     * connections to other servers if you want independent settings for server
     * and client.
     *
     * @param <T> The option type
     * @param option The option
     * @param value The value to set the option to
     * @return this
     */
    public <T> SctpServerAndClientBuilder serverOption(ChannelOption<T> option, T value) {
        Checks.notNull("option", option);
        Checks.notNull("value", value);
        OptionEntry<T> entry = new OptionEntry<>(option, value);
        serverOptions.remove(entry);
        serverOptions.add(entry);
        return this;
    }

    /**
     * Set a channel option for NioSctpChannels created for server connections
     * (Netty's ServerBootstrap) but not to be used for client connections. Use
     * in the case you will create a server that will also make client
     * connections to other servers if you want independent settings for server
     * and client.
     *
     * @param <T> The option type
     * @param option The option
     * @param value The value to set the option to
     * @return this
     */
    public <T> SctpServerAndClientBuilder clientOption(ChannelOption<T> option, T value) {
        Checks.notNull("option", option);
        Checks.notNull("value", value);
        OptionEntry<T> entry = new OptionEntry<>(option, value);
        clientOptions.remove(entry);
        clientOptions.add(entry);
        return this;
    }

    /**
     * Set the port. For clients, this is the port that will be connected to.
     * For servers it is the local port that will be bound.
     *
     * @param port The port
     * @return
     */
    public SctpServerAndClientBuilder onPort(int port) {
        Checks.nonZero("port", port);
        Checks.nonNegative("port", port);
        if (port > 65535) {
            throw new IllegalArgumentException("Invalid port " + port);
        }
        this.port = port;
        return this;
    }

    /**
     * Set the number of event threads that will initially receieve incoming
     * events.
     *
     * @param eventThreads The number of threads
     * @return this
     */
    public SctpServerAndClientBuilder withEventThreads(int eventThreads) {
        Checks.nonZero("eventThreads", eventThreads);
        Checks.nonNegative("eventThreads", eventThreads);
        this.eventThreads = eventThreads;
        return this;
    }

    /**
     * Set the number of worker threads (applies to servers only) that will be
     * used to post-process incoming messages. The default value is to use
     * Netty's default for <code>NioEventLoopGroup</code>, which can also be set
     * by passing -1 here. It is always preferable to know how many threads you
     * want to use based on your hardware configuration.
     *
     * @param workerThreads The number of threads
     * @return this
     */
    public SctpServerAndClientBuilder withWorkerThreads(int workerThreads) {
        Checks.nonZero("workerThreads", workerThreads);
        if (workerThreads < -1) {
            throw new IllegalArgumentException("Negative worker thread count");
        }
        this.workerThreads = workerThreads;
        return this;
    }

    static final class OptionEntry<T> {

        private final ChannelOption<T> option;
        private final T value;

        public OptionEntry(ChannelOption<T> option, T value) {
            this.option = option;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof OptionEntry && ((OptionEntry) o).option.equals(option);
        }

        @Override
        public int hashCode() {
            return option.id();
        }

        Bootstrap apply(Bootstrap bootstrap) {
            return bootstrap.option(option, value);
        }

        ServerBootstrap apply(ServerBootstrap bootstrap) {
            return bootstrap.option(option, value);
        }

        public String toString() {
            return option + "=" + value;
        }
    }
}