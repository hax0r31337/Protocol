package org.cloudburstmc.protocol.bedrock.codec;

import io.netty.buffer.ByteBuf;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.cloudburstmc.protocol.common.util.Preconditions.checkArgument;
import static org.cloudburstmc.protocol.common.util.Preconditions.checkNotNull;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class BedrockCodec {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(BedrockCodec.class);

    @Getter
    private final int protocolVersion;
    @Getter
    private final String minecraftVersion;
    private final BedrockPacketDefinition<? extends BedrockPacket>[] packetsById;
    private final Map<Class<? extends BedrockPacket>, BedrockPacketDefinition<? extends BedrockPacket>> packetsByClass;
    private final Supplier<BedrockCodecHelper> helperFactory;
    @Getter
    private final int raknetProtocolVersion;

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public BedrockPacket tryDecode(BedrockCodecHelper helper, ByteBuf buf, int id) throws PacketSerializeException {
        BedrockPacketDefinition<? extends BedrockPacket> definition = getPacketDefinition(id);
        BedrockPacket packet;
        BedrockPacketSerializer<BedrockPacket> serializer;
        if (definition == null) {
            UnknownPacket unknownPacket = new UnknownPacket();
            unknownPacket.setPacketId(id);
            packet = unknownPacket;
            serializer = (BedrockPacketSerializer) unknownPacket;
        } else {
            packet = definition.getFactory().get();
            serializer = (BedrockPacketSerializer) definition.getSerializer();
        }
        int readIndex = buf.readerIndex();
        boolean hasFailure = false;
        try {
            serializer.deserialize(buf, helper, packet);
        } catch (Exception e) {
            // throw new PacketSerializeException("Error whilst deserializing " + packet, e);
            log.error("Error whilst deserializing " + packet, e);
            hasFailure = true;
        }

        if (buf.isReadable()) {
            if (log.isDebugEnabled()) {
                log.debug(packet.getClass().getSimpleName() + " still has " + buf.readableBytes() + " bytes to read!");
            }
            hasFailure = true;
        }

        if (hasFailure) {
            buf.readerIndex(readIndex);
            UnknownPacket unknownPacket = new UnknownPacket();
            unknownPacket.setPacketId(id);
            unknownPacket.deserialize(buf, helper, unknownPacket);
            packet = unknownPacket;
        }

        return packet;
    }

    @SuppressWarnings("unchecked")
    public <T extends BedrockPacket> void tryEncode(BedrockCodecHelper helper, ByteBuf buf, T packet) throws PacketSerializeException {
        try {
            BedrockPacketSerializer<T> serializer;
            if (packet instanceof UnknownPacket) {
                serializer = (BedrockPacketSerializer<T>) packet;
            } else {
                BedrockPacketDefinition<T> definition = getPacketDefinition((Class<T>) packet.getClass());
                serializer = definition.getSerializer();
            }
            serializer.serialize(buf, helper, packet);
        } catch (Exception e) {
            throw new PacketSerializeException("Error whilst serializing " + packet, e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends BedrockPacket> BedrockPacketDefinition<T> getPacketDefinition(Class<T> packet) {
        checkNotNull(packet, "packet");
        return (BedrockPacketDefinition<T>) packetsByClass.get(packet);
    }

    public BedrockPacketDefinition<? extends BedrockPacket> getPacketDefinition(int id) {
        if (id < 0 || id >= packetsById.length) {
            return null;
        }
        return packetsById[id];
    }

    public BedrockCodecHelper createHelper() {
        return this.helperFactory.get();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();

        builder.packets.putAll(this.packetsByClass);
        builder.protocolVersion = this.protocolVersion;
        builder.raknetProtocolVersion = this.raknetProtocolVersion;
        builder.minecraftVersion = this.minecraftVersion;
        builder.helperFactory = this.helperFactory;

        return builder;
    }

    @SuppressWarnings({"unchecked", "ConstantConditions"})
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Builder {
        private final Map<Class<? extends BedrockPacket>, BedrockPacketDefinition<? extends BedrockPacket>> packets = new IdentityHashMap<>();
        private int protocolVersion = -1;
        private int raknetProtocolVersion = 10;
        private String minecraftVersion = null;
        private Supplier<BedrockCodecHelper> helperFactory;

        public <T extends BedrockPacket> Builder registerPacket(Supplier<T> factory, BedrockPacketSerializer<T> serializer, @NonNegative int id) {
            Class<? extends BedrockPacket> packetClass = factory.get().getClass();

            checkArgument(id >= 0, "id cannot be negative");
            checkArgument(!packets.containsKey(packetClass), "Packet class already registered");

            BedrockPacketDefinition<T> info = new BedrockPacketDefinition<>(id, factory, serializer);

            packets.put(packetClass, info);

            return this;
        }

        public <T extends BedrockPacket> Builder updateSerializer(Class<T> packetClass, BedrockPacketSerializer<T> serializer) {
            BedrockPacketDefinition<T> info = (BedrockPacketDefinition<T>) packets.get(packetClass);
            checkArgument(info != null, "Packet does not exist");
            BedrockPacketDefinition<T> updatedInfo = new BedrockPacketDefinition<>(info.getId(), info.getFactory(), serializer);

            packets.replace(packetClass, info, updatedInfo);

            return this;
        }

        public Builder retainPackets(Class<? extends BedrockPacket>... packets) {
            this.packets.keySet().retainAll(Arrays.asList(packets));

            return this;
        }

        public Builder deregisterPacket(Class<? extends BedrockPacket> packetClass) {
            checkNotNull(packetClass, "packetClass");

            BedrockPacketDefinition<? extends BedrockPacket> info = packets.remove(packetClass);
            return this;
        }

        public Builder protocolVersion(@NonNegative int protocolVersion) {
            checkArgument(protocolVersion >= 0, "protocolVersion cannot be negative");
            this.protocolVersion = protocolVersion;
            return this;
        }

        public Builder raknetProtocolVersion(@NonNegative int version) {
            checkArgument(version >= 0, "raknetProtocolVersion cannot be negative");
            this.raknetProtocolVersion = version;
            return this;
        }

        public Builder minecraftVersion(@NonNull String minecraftVersion) {
            checkNotNull(minecraftVersion, "minecraftVersion");
            checkArgument(!minecraftVersion.isEmpty() && minecraftVersion.split("\\.").length > 2, "Invalid minecraftVersion");
            this.minecraftVersion = minecraftVersion;
            return this;
        }

        public Builder helper(@NonNull Supplier<BedrockCodecHelper> helperFactory) {
            checkNotNull(helperFactory, "helperFactory");
            this.helperFactory = helperFactory;
            return this;
        }

        public BedrockCodec build() {
            checkArgument(protocolVersion >= 0, "No protocol version defined");
            checkNotNull(minecraftVersion, "No Minecraft version defined");
            checkNotNull(helperFactory, "helperFactory cannot be null");
            int largestId = -1;
            for (BedrockPacketDefinition<? extends BedrockPacket> info : packets.values()) {
                if (info.getId() > largestId) {
                    largestId = info.getId();
                }
            }
            checkArgument(largestId > -1, "Must have at least one packet registered");
            BedrockPacketDefinition<? extends BedrockPacket>[] packetsById = new BedrockPacketDefinition[largestId + 1];

            for (BedrockPacketDefinition<? extends BedrockPacket> info : packets.values()) {
                packetsById[info.getId()] = info;
            }
            return new BedrockCodec(protocolVersion, minecraftVersion, packetsById, packets, helperFactory, raknetProtocolVersion);
        }
    }
}
