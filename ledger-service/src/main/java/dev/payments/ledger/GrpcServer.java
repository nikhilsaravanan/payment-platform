package dev.payments.ledger;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcServer implements SmartLifecycle {
    private final Server server;
    private volatile boolean running;
    public GrpcServer(LedgerRpc service,@Value("${ledger.grpc.port:9090}") int port) {
        server=NettyServerBuilder.forPort(port).addService(service).build();
    }
    @Override public void start() {
        try { server.start();running=true; } catch(IOException e) { throw new IllegalStateException("Cannot start ledger gRPC",e); }
    }
    @Override public void stop() {
        running=false;
        server.shutdown();
        try { if(!server.awaitTermination(5,TimeUnit.SECONDS)) server.shutdownNow(); }
        catch(InterruptedException e) { server.shutdownNow();Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return running; }
    public int port() { return server.getPort(); }
}
