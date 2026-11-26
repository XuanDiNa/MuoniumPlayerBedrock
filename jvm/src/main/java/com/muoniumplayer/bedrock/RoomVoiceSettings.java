package com.muoniumplayer.bedrock;
import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.settings.HudConfig;
/** Passive shortcut from Together Listen to the shared settings category. */
public final class RoomVoiceSettings {
 private RoomVoiceSettings(){}
 public static void open(){
  var client=BedrockClient.getInstance();var categories=HudSettingsModel.categories();
  for(int i=0;i<categories.length;i++)if(categories[i].id.equals("voice-broadcast")){HudConfig.hudEditorLastCategory=i;break;}
  client.gui.setScreen(new HostHudEditor(client.gui.screen()));
 }
}
