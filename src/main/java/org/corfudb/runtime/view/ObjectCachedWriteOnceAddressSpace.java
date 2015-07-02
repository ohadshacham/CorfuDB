/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.corfudb.runtime.view;

import org.corfudb.infrastructure.thrift.ExtntInfo;
import org.corfudb.infrastructure.thrift.Hints;
import org.corfudb.runtime.*;
import org.corfudb.runtime.CorfuDBRuntime;
import org.corfudb.runtime.protocols.IServerProtocol;
import org.corfudb.runtime.protocols.logunits.IWriteOnceLogUnit;

import java.util.List;

import org.corfudb.runtime.smr.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.io.IOException;

import java.util.function.Supplier;

import java.util.UUID;
/**
 * This view implements a cached write once address space
 *
 * @author Michael Wei <mwei@cs.ucsd.edu>
 */

public class ObjectCachedWriteOnceAddressSpace implements IWriteOnceAddressSpace {

    private CorfuDBRuntime client;
    private CorfuDBView view;
    private Supplier<CorfuDBView> getView;

	private final Logger log = LoggerFactory.getLogger(org.corfudb.runtime.view.ObjectCachedWriteOnceAddressSpace.class);

    public ObjectCachedWriteOnceAddressSpace(CorfuDBRuntime client)
    {
        this.client = client;
        this.getView = client::getView;
    }

    public ObjectCachedWriteOnceAddressSpace(CorfuDBRuntime client, UUID logID)
    {
        this.client = client;
        this.getView = () -> {
            try {
            return this.client.getView(logID);
            }
            catch (RemoteException re)
            {
                log.warn("Error getting remote view", re);
                return null;
            }
        };
    }

    public ObjectCachedWriteOnceAddressSpace(CorfuDBView view)
    {
        this.view = view;
        this.getView = () -> {
            return this.view;
        };
    }

    private Pair<List<IServerProtocol>, Integer> getChain(long address) {
        //TODO: handle multiple segments
        CorfuDBViewSegment segments =  getView.get().getSegments().get(0);
        int mod = segments.getGroups().size();
        int groupnum =(int) (address % mod);
        return new Pair(segments.getGroups().get(groupnum), mod);
    }

    public void write(long address, Serializable s)
        throws IOException, OverwriteException, TrimmedException, OutOfSpaceException
    {
        //log.warn("write! " + address);
        write(address, Serializer.serialize(s));

        //A successful write means we can just put it in the cache.
        AddressSpaceObjectCache.put(getView.get().getUUID(), address, s);
        /*
        try (ByteArrayOutputStream bs = new ByteArrayOutputStream())
        {
            try (DeflaterOutputStream dos = new DeflaterOutputStream(bs))
            {
                Kryo k = Serializer.kryos.get();
                Output o = new Output(dos, 16384);
                k.writeClassAndObject(o, s);
                o.flush();
                o.close();
                dos.flush();
                dos.finish();
                write(address, bs.toByteArray());
            }
        }*/
    }

    public void write(long address, byte[] data)
        throws OverwriteException, TrimmedException, OutOfSpaceException
    {
       //log.warn("write, lid= " + getView.get().getUUID() +  " ! " + address);

        while (true)
        {
            try {
                //TODO: handle multiple segments
                CorfuDBViewSegment segments =  getView.get().getSegments().get(0);
                int mod = segments.getGroups().size();
                int groupnum =(int) (address % mod);
                List<IServerProtocol> chain = segments.getGroups().get(groupnum);
                //writes have to go to chain in order
                long mappedAddress = address/mod;
                for (IServerProtocol unit : chain)
                {
                    ((IWriteOnceLogUnit)unit).write(mappedAddress,data);
                    return;
                }
            }
            catch (NetworkException e)
            {
                log.warn("Unable to write, requesting new view.", e);
                client.invalidateViewAndWait(e);
            }
        }
    }

    public byte[] read(long address)
        throws UnwrittenException, TrimmedException
    {
        //TODO: cache the layout so we don't have to determine it on every write.
      //  log.info("Read2 from id=" + getView.get().getUUID());
        while (true)
        {
            try {
                byte[] data = null;

             //   data = AddressSpaceCache.get(logID, address);
//                if (data != null) {
  //                  stream.debug("ObjCache hit @ {}", address);
    //                return data;
      //          }

                //TODO: handle multiple segments
                CorfuDBViewSegment segments =  getView.get().getSegments().get(0);
                int mod = segments.getGroups().size();
                int groupnum =(int) (address % mod);
                long mappedAddress = address/mod;

                List<IServerProtocol> chain = segments.getGroups().get(groupnum);
                //reads have to come from last unit in chain
                IWriteOnceLogUnit wolu = (IWriteOnceLogUnit) chain.get(chain.size() - 1);
                data = wolu.read(mappedAddress);
            //    stream.debug("Objcache MISS @ {}", address);
             //   AddressSpaceCache.put(logID, address, data);
                return data;
            }
            catch (NetworkException e)
            {
                log.warn("Unable to read, requesting new view.", e);
                client.invalidateViewAndWait(e);
            }
        }
    }

    public Object readObject(long address)
        throws UnwrittenException, TrimmedException, ClassNotFoundException, IOException
    {
    //    log.info("Read from id=" + getView.get().getUUID());
         Object o = AddressSpaceObjectCache.get(getView.get().getUUID(), address);
         if (o != null) {
             return o; }

         byte[] data = read(address);
         o = Serializer.deserialize(data);
        AddressSpaceObjectCache.put(getView.get().getUUID(), address ,o);
        return o;
         /*
         Kryo k = Serializer.kryos.get();
        stream.debug("ObjCache MISS @ {}", address);

         byte[] data = read(address);
         try (ByteArrayInputStream bais = new ByteArrayInputStream(data))
         {
            try (InflaterInputStream dis = new InflaterInputStream(bais))
            {
                try (Input input = new Input(dis, 16384))
                {
                    o = k.readClassAndObject(input);
                    AddressSpaceObjectCache.put(logID, address ,o);
                    return o;
                }
            }
         }
         */
    }

    @Override
    public Hints readHints(long address)
            throws UnwrittenException, TrimmedException
    {
        //TODO: cache the layout so we don't have to determine it on every write.

        while (true)
        {
            try {
                Pair<List<IServerProtocol>, Integer> logInfo = getChain(address);
                List<IServerProtocol> chain = logInfo.first;
                //writes have to go to chain in order
                long mappedAddress = address/logInfo.second;
                //reads have to come from last unit in chain
                IWriteOnceLogUnit wolu = (IWriteOnceLogUnit) chain.get(chain.size() - 1);
                return wolu.readHints(mappedAddress);
            }
            catch (NetworkException e)
            {
                log.warn("Unable to read, requesting new view.", e);
                client.invalidateViewAndWait(e);
            }
        }
    }

    @Override
    public void setHintsNext(long address, String stream, long nextOffset)
            throws UnwrittenException, TrimmedException
    {
        //TODO: cache the layout so we don't have to determine it on every write.

        while (true)
        {
            try {
                Pair<List<IServerProtocol>, Integer> logInfo = getChain(address);
                List<IServerProtocol> chain = logInfo.first;

                long mappedAddress = address/logInfo.second;
                // TODO: right now, only the last node in a chain of replication contains the in-memory metadata!!!
                IWriteOnceLogUnit wolu = (IWriteOnceLogUnit) chain.get(chain.size() - 1);
                wolu.setHintsNext(mappedAddress, stream, nextOffset);
                return;
            }
            catch (NetworkException e)
            {
                log.warn("Unable to read, requesting new view.", e);
                client.invalidateViewAndWait(e);
            }
        }
    }

    @Override
    public void setHintsTxDec(long address, boolean dec)
            throws UnwrittenException, TrimmedException
    {
        //TODO: cache the layout so we don't have to determine it on every write.

        while (true)
        {
            try {
                Pair<List<IServerProtocol>, Integer> logInfo = getChain(address);
                List<IServerProtocol> chain = logInfo.first;

                long mappedAddress = address/logInfo.second;
                // TODO: right now, only the last node in a chain of replication contains the in-memory metadata!!!
                IWriteOnceLogUnit wolu = (IWriteOnceLogUnit) chain.get(chain.size() - 1);
                wolu.setHintsTxDec(mappedAddress, dec);
                return;
            }
            catch (NetworkException e)
            {
                log.warn("Unable to read, requesting new view.", e);
                client.invalidateViewAndWait(e);
            }
        }
    }
}


