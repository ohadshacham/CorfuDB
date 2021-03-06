package org.corfudb.infrastructure;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.wireprotocol.CorfuMsg;
import org.corfudb.protocols.wireprotocol.CorfuSetEpochMsg;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * The netty server router routes incoming messages to registered roles using
 * the
 * Created by mwei on 12/1/15.
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyServerRouter extends ChannelInboundHandlerAdapter
implements IServerRouter {

    /** This map stores the mapping from message type to netty server handler. */
    Map<CorfuMsg.CorfuMsgType, IServer> handlerMap;

    BaseServer baseServer;

    /** The epoch of this router. This is managed by the base server implementation. */
    @Getter
    @Setter
    long epoch;

    public NettyServerRouter()
    {
        handlerMap = new ConcurrentHashMap<>();
        baseServer = new BaseServer(this);
        addServer(baseServer);
    }

    /** Add a new netty server handler to the router.
     *
     * @param server The server to add.
     */
    public void addServer(IServer server) {
        // Iterate through all types of CorfuMsgType, registering the handler
        Arrays.<CorfuMsg.CorfuMsgType>stream(CorfuMsg.CorfuMsgType.values())
                .forEach(x -> {
                    if (x.handler.isInstance(server))
                    {
                        handlerMap.put(x, server);
                        log.trace("Registered {} to handle messages of type {}", server, x);
                    }
                });
    }

    /** Send a netty message through this router, setting the fields in the outgoing message.
     *
     * @param ctx       Channel handler context to use.
     * @param inMsg     Incoming message to respond to.
     * @param outMsg    Outgoing message.
     */
    public void sendResponse(ChannelHandlerContext ctx, CorfuMsg inMsg, CorfuMsg outMsg)
    {
        outMsg.copyBaseFields(inMsg);
        outMsg.setEpoch(epoch);
        ctx.writeAndFlush(outMsg);
        log.trace("Sent response: {}", outMsg);
    }

    /** Validate the epoch of a CorfuMsg, and send a WRONG_EPOCH response if
     * the server is in the wrong epoch. Ignored if the message type is reset (which
     * is valid in any epoch).
     * @param msg   The incoming message to validate.
     * @param ctx   The context of the channel handler.
     * @return      True, if the epoch is correct, but false otherwise.
     */
    public boolean validateEpoch(CorfuMsg msg, ChannelHandlerContext ctx)
    {
        if (!msg.getMsgType().ignoreEpoch && msg.getEpoch() != epoch)
        {
            sendResponse(ctx, msg, new CorfuSetEpochMsg(CorfuMsg.CorfuMsgType.WRONG_EPOCH,
                    getEpoch()));
            log.trace("Incoming message with wrong epoch, got {}, expected {}, message was: {}",
                    msg.getEpoch(), epoch, msg);
            return false;
        }
        return true;
    }

    /**
     * Handle an incoming message read on the channel.
     * @param ctx   Channel handler context
     * @param msg   The incoming message on that channel.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean handled = false;
        try {
            // The incoming message should have been transformed to a CorfuMsg earlier in the pipeline.
            CorfuMsg m = ((CorfuMsg) msg);
            // We get the handler for this message from the map
            IServer handler = handlerMap.get(m.getMsgType());
            if (handler == null)
            {
                // The message was unregistered, we are dropping it.
                log.warn("Received unregistered message {}, dropping", m);
            }
            else
            {
                if (validateEpoch(m, ctx)) {
                    // Route the message to the handler.
                    log.trace("Message routed to {}: {}", handler.getClass().getSimpleName(), msg);
                    handler.handleMessage(m, ctx, this);
                    handled = true;
                }
            }
        }
        catch (Exception e)
        {
            log.error("Exception during read!" , e);
        }
        finally {
            if (!handled && msg != null)
            {
                ((CorfuMsg) msg).release();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in handling inbound message, {}", cause);
        ctx.close();
    }

}
