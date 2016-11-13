package us.myles.ViaVersion.sponge.platform;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.spongepowered.api.MinecraftVersion;
import org.spongepowered.api.Sponge;
import us.myles.ViaVersion.api.Pair;
import us.myles.ViaVersion.api.Via;
import us.myles.ViaVersion.api.platform.ViaInjector;
import us.myles.ViaVersion.sponge.handlers.SpongeChannelInitializer;
import us.myles.ViaVersion.util.ListWrapper;
import us.myles.ViaVersion.util.ReflectionUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class SpongeViaInjector implements ViaInjector {
    private List<ChannelFuture> injectedFutures = new ArrayList<>();
    private List<Pair<Field, Object>> injectedLists = new ArrayList<>();

    @Override
    public void inject() throws Exception {
        try {
            Object connection = getServerConnection();
            if (connection == null) {
                throw new Exception("We failed to find the core component 'ServerConnection', please file an issue on our GitHub.");
            }
            for (Field field : connection.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                final Object value = field.get(connection);
                if (value instanceof List) {
                    // Inject the list
                    List wrapper = new ListWrapper((List) value) {
                        @Override
                        public synchronized void handleAdd(Object o) {
                            synchronized (this) {
                                if (o instanceof ChannelFuture) {
                                    try {
                                        injectChannelFuture((ChannelFuture) o);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                    };
                    injectedLists.add(new Pair<>(field, connection));
                    field.set(connection, wrapper);
                    // Iterate through current list
                    synchronized (wrapper) {
                        for (Object o : (List) value) {
                            if (o instanceof ChannelFuture) {
                                injectChannelFuture((ChannelFuture) o);
                            } else {
                                break; // not the right list.
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            Via.getPlatform().getLogger().severe("Unable to inject ViaVersion, please post these details on our GitHub and ensure you're using a compatible server version.");
            throw e;
        }
    }

    private void injectChannelFuture(ChannelFuture future) throws Exception {
        try {
            List<String> names = future.channel().pipeline().names();
            ChannelHandler bootstrapAcceptor = null;
            // Pick best
            for (String name : names) {
                ChannelHandler handler = future.channel().pipeline().get(name);
                try {
                    ReflectionUtil.get(handler, "childHandler", ChannelInitializer.class);
                    bootstrapAcceptor = handler;
                } catch (Exception e) {
                    // Not this one
                }
            }
            // Default to first (Also allows blame to work)
            if (bootstrapAcceptor == null) {
                bootstrapAcceptor = future.channel().pipeline().first();
            }
            try {
                ChannelInitializer<SocketChannel> oldInit = ReflectionUtil.get(bootstrapAcceptor, "childHandler", ChannelInitializer.class);
                ChannelInitializer newInit = new SpongeChannelInitializer(oldInit);

                ReflectionUtil.set(bootstrapAcceptor, "childHandler", newInit);
                injectedFutures.add(future);
            } catch (NoSuchFieldException e) {
                throw new Exception("Unable to find core component 'childHandler', please check your plugins. issue: " + bootstrapAcceptor.getClass().getName());

            }
        } catch (Exception e) {
            Via.getPlatform().getLogger().severe("We failed to inject ViaVersion, have you got late-bind enabled with something else?");
            throw e;
        }
    }

    @Override
    public void uninject() {
        // TODO: Uninject from players currently online
        for (ChannelFuture future : injectedFutures) {
            List<String> names = future.channel().pipeline().names();
            ChannelHandler bootstrapAcceptor = null;
            // Pick best
            for (String name : names) {
                ChannelHandler handler = future.channel().pipeline().get(name);
                try {
                    ChannelInitializer<SocketChannel> oldInit = ReflectionUtil.get(handler, "childHandler", ChannelInitializer.class);
                    if (oldInit instanceof SpongeChannelInitializer) {
                        bootstrapAcceptor = handler;
                    }
                } catch (Exception e) {
                    // Not this one
                }
            }
            // Default to first
            if (bootstrapAcceptor == null) {
                bootstrapAcceptor = future.channel().pipeline().first();
            }

            try {
                ChannelInitializer<SocketChannel> oldInit = ReflectionUtil.get(bootstrapAcceptor, "childHandler", ChannelInitializer.class);
                if (oldInit instanceof SpongeChannelInitializer) {
                    ReflectionUtil.set(bootstrapAcceptor, "childHandler", ((SpongeChannelInitializer) oldInit).getOriginal());
                }
            } catch (Exception e) {
                Via.getPlatform().getLogger().severe("Failed to remove injection handler, reload won't work with connections, please reboot!");
            }
        }
        injectedFutures.clear();

        for (Pair<Field, Object> pair : injectedLists) {
            try {
                Object o = pair.getKey().get(pair.getValue());
                if (o instanceof ListWrapper) {
                    pair.getKey().set(pair.getValue(), ((ListWrapper) o).getOriginalList());
                }
            } catch (IllegalAccessException e) {
                Via.getPlatform().getLogger().severe("Failed to remove injection, reload won't work with connections, please reboot!");
            }
        }

        injectedLists.clear();
    }

    public static Object getServer() throws Exception {
        return Sponge.getServer();
    }

    @Override
    public int getServerProtocolVersion() throws Exception {
        MinecraftVersion mcv = Sponge.getPlatform().getMinecraftVersion();
        try {
            return (int) mcv.getClass().getDeclaredMethod("getProtocol").invoke(mcv);
        } catch (Exception e) {
            throw new Exception("Failed to get server protocol", e);
        }
    }

    @Override
    public String getEncoderName() {
        return "encoder";
    }

    @Override
    public String getDecoderName() {
        return "decoder";
    }

    public static Object getServerConnection() throws Exception {
        Class<?> serverClazz = Class.forName("net.minecraft.server.MinecraftServer");
        Object server = getServer();
        Object connection = null;
        for (Method m : serverClazz.getDeclaredMethods()) {
            if (m.getReturnType() != null) {
                if (m.getReturnType().getSimpleName().equals("NetworkSystem")) {
                    if (m.getParameterTypes().length == 0) {
                        connection = m.invoke(server);
                    }
                }
            }
        }
        return connection;
    }

}
