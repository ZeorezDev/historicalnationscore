package dev.zeorez.historicalnations.gui;

import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

/** Only navigation travels from the client. The server chooses the rows and allowed operations. */
public record PanelQuery(View view, UUID target, Sort sort, boolean descending, String search,
                         int page, int size, int serial) {
    public enum View { HOME, TOWNS, NATIONS, TOWN, NATION, RESIDENTS, MEMBER, MEMBER_TOWNS, PLAYER, TOWN_OFFICE, NATION_OFFICE }
    public enum Sort { NAME, PEOPLE, TOWNS, LAND, TREASURY }
    public PanelQuery {
        if (page < 0 || size < 1 || size > 12 || search.length() > 32 || serial < 0 ||
                search.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid directory request");
    }
    public static PanelQuery open(View view, UUID target) {
        return new PanelQuery(view,target,Sort.NAME,false,"",0,6,0);
    }
    public void write(FriendlyByteBuf b) {
        b.writeEnum(view); b.writeNullable(target,FriendlyByteBuf::writeUUID); b.writeEnum(sort);
        b.writeBoolean(descending); b.writeUtf(search,32); b.writeVarInt(page); b.writeVarInt(size); b.writeVarInt(serial);
    }
    public static PanelQuery read(FriendlyByteBuf b) {
        return new PanelQuery(b.readEnum(View.class),b.readNullable(FriendlyByteBuf::readUUID),b.readEnum(Sort.class),
                b.readBoolean(),b.readUtf(32),b.readVarInt(),b.readVarInt(),b.readVarInt());
    }
}
