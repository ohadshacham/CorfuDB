package org.corfudb.infrastructure;

import io.netty.channel.ChannelHandlerContext;
import org.corfudb.protocols.wireprotocol.CorfuMsg;

/**
 * Created by mwei on 12/13/15.
 */
public interface IServerRouter {
    void sendResponse(ChannelHandlerContext ctx, CorfuMsg inMsg, CorfuMsg outMsg);

    /** Get the current epoch. */
    long getEpoch();

    /** Set the current epoch. */
    void setEpoch(long newEpoch);
}
