package dev.zeorez.hntests;

import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.client.CityEvaluationScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.UUID;
import static dev.zeorez.hntests.PrototypeServerTest.check;

/** Exercises the real screen and Minecraft widgets without requiring a network transport. */
@Mod.EventBusSubscriber(modid="hn_integration_tests",value=Dist.CLIENT)
public final class CityWidgetTest {
    private static int step;
    private static java.lang.reflect.Field field(String name) throws ReflectiveOperationException {
        var f=CityEvaluationScreen.class.getDeclaredField(name);
        f.setAccessible(true);return f;
    }
    private static Button button(Screen screen,String key) {
        String name=Component.translatable("hn.panel."+key).getString();
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b->b.getMessage().getString().equals(name)).findFirst().orElseThrow();
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("hn.test.cityWidgets") || event.phase!=TickEvent.Phase.END || step>2) return;
        var mc=Minecraft.getInstance();
        if (mc.getOverlay()!=null) return;
        try {
            if(step==0 && mc.screen instanceof TitleScreen) {
                var constructor=CityEvaluationScreen.class.getDeclaredConstructor(Screen.class);
                constructor.setAccessible(true);
                var screen=constructor.newInstance(mc.screen);
                var town=UUID.randomUUID();
                var data=new CompoundTag();
                data.putUUID("town",town); data.putString("name","Kumanovo"); data.putString("mayor","Mayor");
                data.putInt("level",1); data.putInt("score",-1);
                field("data").set(screen,data); field("town").set(screen,town);
                var load=CityEvaluationScreen.class.getDeclaredMethod("loadDraft");
                load.setAccessible(true);load.invoke(screen);
                mc.setScreen(screen);step=1;
            } else if(step==1 && mc.screen instanceof CityEvaluationScreen screen) {
                var sliders=screen.children().stream().filter(AbstractSliderButton.class::isInstance)
                        .map(AbstractSliderButton.class::cast).toList();
                check(sliders.size()==5,"five real sliders");
                for (var slider:sliders) {
                    slider.mouseClicked(slider.getX()+slider.getWidth()-2,slider.getY()+6,0);
                    slider.mouseReleased(slider.getX()+slider.getWidth()-2,slider.getY()+6,0);
                }
                check(java.util.Arrays.equals((int[])field("scores").get(screen),new int[]{30,20,20,20,10}),"slider maxima");
                var boxes=screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
                boxes.get(0).setValue("10");
                check(field("rating").getInt(screen)==4 && field("level").getInt(screen)==1,"80-point suggestion independent of level");
                ((Button)field("ratingButton").get(screen)).onPress();
                check(!button(screen,"save_review").active,"manual override needs a reason");
                button(screen,"review_notes").onPress();
                screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow()
                        .setValue("Architectural distinction");
                check(button(screen,"save_review").active,"reason unlocks save");
                step=2;
            } else if(step==2) {
                LogUtils.getLogger().info("HN INTEGRATION PASS cityWidgets");
                step=3; mc.stop();
            }
        } catch(Exception | AssertionError ex) {
            LogUtils.getLogger().error("HN INTEGRATION FAIL cityWidgets",ex); step=3;mc.stop();
        }
    }
}
