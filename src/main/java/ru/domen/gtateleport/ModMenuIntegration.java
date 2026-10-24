package ru.domen.gtateleport;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** The settings button in Mod Menu's mod list. Only loaded when Mod Menu is there. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SwitchConfigScreen::new;
	}
}
