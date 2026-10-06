package io.jettra.store.cluster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Protocolo de serialización binaria ultra-rápida y de cero sobrecarga para el clúster JettraStore.
 * Estructura de trama:
 * [2B Magic: 0x4A 0x54 ('JT')]
 * [1B FrameType]
 * [8B Term]
 * [8B LogIndex]
 * [8B Timestamp]
 * [UTF senderNodeId]
 * [UTF databaseName]
 * [UTF collectionName]
 * [UTF key]
 * [4B PayloadLength]
 * [NB Payload Bytes]
 */
public final class JettraClusterProtocol {

    public static final short MAGIC = 0x4A54; // 'JT'

    public static void writeFrame(OutputStream out, JettraRaftFrame frame) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeShort(MAGIC);
        dos.writeByte(frame.frameType());
        dos.writeLong(frame.term());
        dos.writeLong(frame.logIndex());
        dos.writeLong(frame.timestamp());
        dos.writeUTF(frame.senderNodeId() != null ? frame.senderNodeId() : "");
        dos.writeUTF(frame.databaseName() != null ? frame.databaseName() : "");
        dos.writeUTF(frame.collectionName() != null ? frame.collectionName() : "");
        dos.writeUTF(frame.key() != null ? frame.key() : "");

        byte[] payload = frame.payload();
        if (payload != null && payload.length > 0) {
            dos.writeInt(payload.length);
            dos.write(payload);
        } else {
            dos.writeInt(0);
        }
        dos.flush();
    }

    public static JettraRaftFrame readFrame(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        short magic = dis.readShort();
        if (magic != MAGIC) {
            throw new IOException("Trama inválida de clúster JettraStore: magic 0x" + Integer.toHexString(magic));
        }
        byte frameType = dis.readByte();
        long term = dis.readLong();
        long logIndex = dis.readLong();
        long timestamp = dis.readLong();
        String senderNodeId = dis.readUTF();
        String databaseName = dis.readUTF();
        String collectionName = dis.readUTF();
        String key = dis.readUTF();
        int payloadLen = dis.readInt();
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            dis.readFully(payload);
        }
        return new JettraRaftFrame(frameType, term, logIndex, senderNodeId, databaseName, collectionName, key, payload, timestamp);
    }
}
