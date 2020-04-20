/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client.subsystem.sftp.impl;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.subsystem.sftp.SftpVersionSelector;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.subsystem.sftp.SftpConstants;
import org.apache.sshd.common.subsystem.sftp.extensions.ParserUtils;
import org.apache.sshd.common.subsystem.sftp.extensions.VersionsParser.Versions;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class DefaultSftpClient extends AbstractSftpClient {
    private final ClientSession clientSession;
    private final ChannelSubsystem channel;
    private final Map<Integer, Buffer> messages = new HashMap<>();
    private final AtomicInteger cmdId = new AtomicInteger(100);
    private final Buffer receiveBuffer = new ByteArrayBuffer();
    private final AtomicInteger versionHolder = new AtomicInteger(0);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final NavigableMap<String, byte[]> extensions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final NavigableMap<String, byte[]> exposedExtensions = Collections.unmodifiableNavigableMap(extensions);
    private Charset nameDecodingCharset = DEFAULT_NAME_DECODING_CHARSET;

    public DefaultSftpClient(ClientSession clientSession) throws IOException {
        this.nameDecodingCharset = PropertyResolverUtils.getCharset(
                clientSession, NAME_DECODING_CHARSET, DEFAULT_NAME_DECODING_CHARSET);
        this.clientSession = Objects.requireNonNull(clientSession, "No client session");
        this.channel = new SftpChannelSubsystem();
        clientSession.getService(ConnectionService.class).registerChannel(channel);

        long initializationTimeout = clientSession.getLongProperty(
                SFTP_CHANNEL_OPEN_TIMEOUT, DEFAULT_CHANNEL_OPEN_TIMEOUT);
        this.channel.open().verify(initializationTimeout);
        this.channel.onClose(() -> {
            synchronized (messages) {
                closing.set(true);
                messages.notifyAll();
            }

            if (versionHolder.get() <= 0) {
                log.warn("onClose({}) closed before version negotiated", channel);
            }
        });

        try {
            init(initializationTimeout);
        } catch (IOException | RuntimeException e) {
            this.channel.close(true);
            throw e;
        }
    }

    @Override
    public int getVersion() {
        return versionHolder.get();
    }

    @Override
    public ClientSession getClientSession() {
        return clientSession;
    }

    @Override
    public ClientChannel getClientChannel() {
        return channel;
    }

    @Override
    public NavigableMap<String, byte[]> getServerExtensions() {
        return exposedExtensions;
    }

    @Override
    public Charset getNameDecodingCharset() {
        return nameDecodingCharset;
    }

    @Override
    public void setNameDecodingCharset(Charset nameDecodingCharset) {
        this.nameDecodingCharset = Objects.requireNonNull(nameDecodingCharset, "No charset provided");
    }

    @Override
    public boolean isClosing() {
        return closing.get();
    }

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        if (isOpen()) {
            this.channel.close(false);
        }
    }

    /**
     * Receive binary data
     * 
     * @param  buf         The buffer for the incoming data
     * @param  start       Offset in buffer to place the data
     * @param  len         Available space in buffer for the data
     * @return             Actual size of received data
     * @throws IOException If failed to receive incoming data
     */
    protected int data(byte[] buf, int start, int len) throws IOException {
        Buffer incoming = new ByteArrayBuffer(buf, start, len);
        // If we already have partial data, we need to append it to the buffer and use it
        if (receiveBuffer.available() > 0) {
            receiveBuffer.putBuffer(incoming);
            incoming = receiveBuffer;
        }

        // Process commands
        int rpos = incoming.rpos();
        boolean traceEnabled = log.isTraceEnabled();
        for (int count = 1; receive(incoming); count++) {
            if (traceEnabled) {
                log.trace("data({}) Processed {} data messages", getClientChannel(), count);
            }
        }

        int read = incoming.rpos() - rpos;
        // Compact and add remaining data
        receiveBuffer.compact();
        if ((receiveBuffer != incoming) && (incoming.available() > 0)) {
            receiveBuffer.putBuffer(incoming);
        }

        return read;
    }

    /**
     * Read SFTP packets from buffer
     *
     * @param  incoming    The received {@link Buffer}
     * @return             {@code true} if data from incoming buffer was processed
     * @throws IOException if failed to process the buffer
     * @see                #process(Buffer)
     */
    protected boolean receive(Buffer incoming) throws IOException {
        int rpos = incoming.rpos();
        int wpos = incoming.wpos();
        ClientSession session = getClientSession();
        session.resetIdleTimeout();

        if ((wpos - rpos) > 4) {
            int length = incoming.getInt();
            if (length < 5) {
                throw new IOException("Illegal sftp packet length: " + length);
            }
            if (length > (8 * SshConstants.SSH_REQUIRED_PAYLOAD_PACKET_LENGTH_SUPPORT)) {
                throw new StreamCorruptedException("Illogical sftp packet length: " + length);
            }
            if ((wpos - rpos) >= (length + 4)) {
                incoming.rpos(rpos);
                incoming.wpos(rpos + 4 + length);
                process(incoming);
                incoming.rpos(rpos + 4 + length);
                incoming.wpos(wpos);
                return true;
            }
        }
        incoming.rpos(rpos);
        return false;
    }

    /**
     * Process an SFTP packet
     *
     * @param  incoming    The received {@link Buffer}
     * @throws IOException if failed to process the buffer
     */
    protected void process(Buffer incoming) throws IOException {
        // create a copy of the buffer in case it is being re-used
        Buffer buffer = new ByteArrayBuffer(incoming.available() + Long.SIZE, false);
        buffer.putBuffer(incoming);

        int rpos = buffer.rpos();
        int length = buffer.getInt();
        int type = buffer.getUByte();
        Integer id = buffer.getInt();
        buffer.rpos(rpos);

        if (log.isTraceEnabled()) {
            log.trace("process({}) id={}, type={}, len={}",
                    getClientChannel(), id, SftpConstants.getCommandMessageName(type), length);
        }

        synchronized (messages) {
            messages.put(id, buffer);
            messages.notifyAll();
        }
    }

    @Override
    public int send(int cmd, Buffer buffer) throws IOException {
        int id = cmdId.incrementAndGet();
        int len = buffer.available();
        if (log.isTraceEnabled()) {
            log.trace("send({}) cmd={}, len={}, id={}",
                    getClientChannel(), SftpConstants.getCommandMessageName(cmd), len, id);
        }

        Buffer buf;
        int hdr = Integer.BYTES /* length */ + 1 /* cmd */ + Integer.BYTES /* id */;
        if (buffer.rpos() >= hdr) {
            int wpos = buffer.wpos();
            int s = buffer.rpos() - hdr;
            buffer.rpos(s);
            buffer.wpos(s);
            buffer.putInt(1 /* cmd */ + Integer.BYTES /* id */ + len); // length
            buffer.putByte((byte) (cmd & 0xFF)); // cmd
            buffer.putInt(id); // id
            buffer.wpos(wpos);
            buf = buffer;
        } else {
            buf = new ByteArrayBuffer(hdr + len);
            buf.putInt(1 /* cmd */ + Integer.BYTES /* id */ + len);
            buf.putByte((byte) (cmd & 0xFF));
            buf.putInt(id);
            buf.putBuffer(buffer);
        }
        channel.getAsyncIn().writePacket(buf).verify();
        return id;
    }

    @Override
    public Buffer receive(int id) throws IOException {
        Session session = getClientSession();
        long idleTimeout = PropertyResolverUtils.getLongProperty(
                session, FactoryManager.IDLE_TIMEOUT, FactoryManager.DEFAULT_IDLE_TIMEOUT);
        if (idleTimeout <= 0L) {
            idleTimeout = FactoryManager.DEFAULT_IDLE_TIMEOUT;
        }

        boolean traceEnabled = log.isTraceEnabled();
        for (int count = 1;; count++) {
            if (isClosing() || (!isOpen())) {
                throw new SshException("Channel is being closed");
            }

            Buffer buffer = receive(id, idleTimeout);
            if (buffer != null) {
                return buffer;
            }

            if (traceEnabled) {
                log.trace("receive({}) check iteration #{} for id={}", this, count, id);
            }
        }
    }

    @Override
    public Buffer receive(int id, long idleTimeout) throws IOException {
        synchronized (messages) {
            Buffer buffer = messages.remove(id);
            if (buffer != null) {
                return buffer;
            }
            if (idleTimeout > 0) {
                try {
                    messages.wait(idleTimeout);
                } catch (InterruptedException e) {
                    throw (IOException) new InterruptedIOException("Interrupted while waiting for messages").initCause(e);
                }
            }
        }
        return null;
    }

    protected void init(long initializationTimeout) throws IOException {
        ValidateUtils.checkTrue(initializationTimeout > 0L, "Invalid initialization timeout: %d", initializationTimeout);

        // Send init packet
        Buffer buf = new ByteArrayBuffer(9);
        buf.putInt(5);
        buf.putByte((byte) SftpConstants.SSH_FXP_INIT);
        buf.putInt(SftpConstants.SFTP_V6);
        channel.getAsyncIn().writePacket(buf).verify();

        Buffer buffer;
        Integer reqId;
        synchronized (messages) {
            /*
             * We need to use a timeout since if the remote server does not support SFTP, we will not know it
             * immediately. This is due to the fact that the request for the subsystem does not contain a reply as to
             * its success or failure. Thus, the SFTP channel is created by the client, but there is no one on the other
             * side to reply - thus the need for the timeout
             */
            for (long remainingTimeout = initializationTimeout;
                 (remainingTimeout > 0L) && messages.isEmpty() && (!isClosing()) && isOpen();) {
                try {
                    long sleepStart = System.nanoTime();
                    messages.wait(remainingTimeout);
                    long sleepEnd = System.nanoTime();
                    long sleepDuration = sleepEnd - sleepStart;
                    long sleepMillis = TimeUnit.NANOSECONDS.toMillis(sleepDuration);
                    if (sleepMillis < 1L) {
                        remainingTimeout--;
                    } else {
                        remainingTimeout -= sleepMillis;
                    }
                } catch (InterruptedException e) {
                    throw (IOException) new InterruptedIOException(
                            "Interrupted init() while " + remainingTimeout + " msec. remaining").initCause(e);
                }
            }

            if (isClosing() || (!isOpen())) {
                throw new EOFException("Closing while await init message");
            }

            if (messages.isEmpty()) {
                throw new SocketTimeoutException(
                        "No incoming initialization response received within " + initializationTimeout + " msec.");
            }

            Collection<Integer> ids = messages.keySet();
            Iterator<Integer> iter = ids.iterator();
            reqId = iter.next();
            buffer = messages.remove(reqId);
        }

        int length = buffer.getInt();
        int type = buffer.getUByte();
        int id = buffer.getInt();
        boolean traceEnabled = log.isTraceEnabled();
        if (traceEnabled) {
            log.trace("init({}) id={} type={} len={}",
                    getClientChannel(), id, SftpConstants.getCommandMessageName(type), length);
        }

        if (type == SftpConstants.SSH_FXP_VERSION) {
            if ((id < SftpConstants.SFTP_V3) || (id > SftpConstants.SFTP_V6)) {
                throw new SshException("Unsupported sftp version " + id);
            }
            versionHolder.set(id);

            if (traceEnabled) {
                log.trace("init({}) version={}", getClientChannel(), versionHolder);
            }

            while (buffer.available() > 0) {
                String name = buffer.getString();
                byte[] data = buffer.getBytes();
                if (traceEnabled) {
                    log.trace("init({}) added extension={}", getClientChannel(), name);
                }
                extensions.put(name, data);
            }
        } else if (type == SftpConstants.SSH_FXP_STATUS) {
            int substatus = buffer.getInt();
            String msg = buffer.getString();
            String lang = buffer.getString();
            if (traceEnabled) {
                log.trace("init({})[id={}] - status: {} [{}] {}",
                        getClientChannel(), id, SftpConstants.getStatusName(substatus), lang, msg);
            }

            throwStatusException(SftpConstants.SSH_FXP_INIT, id, substatus, msg, lang);
        } else {
            IOException err = handleUnexpectedPacket(
                    SftpConstants.SSH_FXP_INIT, SftpConstants.SSH_FXP_VERSION, id, type, length, buffer);
            if (err != null) {
                throw err;
            }

        }
    }

    /**
     * @param  selector    The {@link SftpVersionSelector} to use - ignored if {@code null}
     * @return             The selected version (may be same as current)
     * @throws IOException If failed to negotiate
     */
    public int negotiateVersion(SftpVersionSelector selector) throws IOException {
        boolean debugEnabled = log.isDebugEnabled();
        ClientChannel clientChannel = getClientChannel();
        int current = getVersion();
        if (selector == null) {
            if (debugEnabled) {
                log.debug("negotiateVersion({}) no selector to override current={}", clientChannel, current);
            }
            return current;
        }

        Map<String, ?> parsed = getParsedServerExtensions();
        Collection<String> extensions = ParserUtils.supportedExtensions(parsed);
        List<Integer> availableVersions = Collections.emptyList();
        if ((GenericUtils.size(extensions) > 0)
                && extensions.contains(SftpConstants.EXT_VERSION_SELECT)) {
            Versions vers = GenericUtils.isEmpty(parsed)
                    ? null
                    : (Versions) parsed.get(SftpConstants.EXT_VERSIONS);
            availableVersions = (vers == null)
                    ? Collections.singletonList(current)
                    : vers.resolveAvailableVersions(current);
        } else {
            availableVersions = Collections.singletonList(current);
        }

        ClientSession session = getClientSession();
        int selected = selector.selectVersion(session, current, availableVersions);
        if (debugEnabled) {
            log.debug("negotiateVersion({}) current={} {} -> {}",
                    clientChannel, current, availableVersions, selected);
        }

        if (selected == current) {
            return current;
        }

        if (!availableVersions.contains(selected)) {
            throw new StreamCorruptedException(
                    "Selected version (" + selected + ") not part of available: " + availableVersions);
        }

        String verVal = String.valueOf(selected);
        Buffer buffer = new ByteArrayBuffer(
                Integer.BYTES + SftpConstants.EXT_VERSION_SELECT.length() // extension name
                                            + Integer.BYTES + verVal.length() + Byte.SIZE,
                false);
        buffer.putString(SftpConstants.EXT_VERSION_SELECT);
        buffer.putString(verVal);
        checkCommandStatus(SftpConstants.SSH_FXP_EXTENDED, buffer);
        versionHolder.set(selected);
        return selected;
    }

    private class SftpChannelSubsystem extends ChannelSubsystem {

        SftpChannelSubsystem() {
            super(SftpConstants.SFTP_SUBSYSTEM_NAME);
        }

        @Override
        protected void doOpen() throws IOException {
            String systemName = getSubsystem();
            Session session = getSession();
            boolean wantReply = this.getBooleanProperty(
                    REQUEST_SUBSYSTEM_REPLY, DEFAULT_REQUEST_SUBSYSTEM_REPLY);
            Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST,
                    Channel.CHANNEL_SUBSYSTEM.length() + systemName.length() + Integer.SIZE);
            buffer.putInt(getRecipient());
            buffer.putString(Channel.CHANNEL_SUBSYSTEM);
            buffer.putBoolean(wantReply);
            buffer.putString(systemName);
            addPendingRequest(Channel.CHANNEL_SUBSYSTEM, wantReply);
            writePacket(buffer);

            asyncIn = new ChannelAsyncOutputStream(this, SshConstants.SSH_MSG_CHANNEL_DATA) {
                @SuppressWarnings("synthetic-access")
                @Override
                protected CloseFuture doCloseGracefully() {
                    try {
                        sendEof();
                    } catch (IOException e) {
                        Session session = getSession();
                        session.exceptionCaught(e);
                    }
                    return super.doCloseGracefully();
                }

                @Override
                protected Buffer createSendBuffer(Buffer buffer, Channel channel, long length) {
                    if (buffer.rpos() >= 9 && length == buffer.available()) {
                        int rpos = buffer.rpos();
                        int wpos = buffer.wpos();
                        buffer.rpos(rpos - 9);
                        buffer.wpos(rpos - 8);
                        buffer.putInt(channel.getRecipient());
                        buffer.putInt(length);
                        buffer.wpos(wpos);
                        return buffer;
                    } else {
                        return super.createSendBuffer(buffer, channel, length);
                    }
                }
            };
            out = new OutputStream() {
                private final byte[] singleByte = new byte[1];

                @Override
                public void write(int b) throws IOException {
                    synchronized (singleByte) {
                        singleByte[0] = (byte) b;
                        write(singleByte);
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    data(b, off, len);
                }
            };
            err = new ByteArrayOutputStream();
        }
    }
}
