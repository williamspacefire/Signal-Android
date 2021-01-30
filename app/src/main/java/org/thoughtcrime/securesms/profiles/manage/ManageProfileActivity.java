package org.thoughtcrime.securesms.profiles.manage;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.NavGraph;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;

import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.reactions.any.ReactWithAnyEmojiBottomSheetDialogFragment;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;

/**
 * Activity that manages the local user's profile, as accessed via the settings.
 */
public class ManageProfileActivity extends PassphraseRequiredActivity implements ReactWithAnyEmojiBottomSheetDialogFragment.Callback {

  private final DynamicTheme dynamicTheme = new DynamicNoActionBarTheme();

  public static final String START_AT_USERNAME = "start_at_username";

  public static @NonNull Intent getIntent(@NonNull Context context) {
    return new Intent(context, ManageProfileActivity.class);
  }

  public static @NonNull Intent getIntentForUsernameEdit(@NonNull Context context) {
    Intent intent = new Intent(context, ManageProfileActivity.class);
    intent.putExtra(START_AT_USERNAME, true);
    return intent;
  }

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    dynamicTheme.onCreate(this);

    setContentView(R.layout.manage_profile_activity);

    if (bundle == null) {
      Bundle   extras = getIntent().getExtras();
      NavGraph graph  = Navigation.findNavController(this, R.id.nav_host_fragment).getGraph();

      Navigation.findNavController(this, R.id.nav_host_fragment).setGraph(graph, extras != null ? extras : new Bundle());

      if (extras != null && extras.getBoolean(START_AT_USERNAME, false)) {
        NavDirections action = ManageProfileFragmentDirections.actionManageUsername();
        Navigation.findNavController(this, R.id.nav_host_fragment).navigate(action);
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
  }

  @Override
  public void onReactWithAnyEmojiDialogDismissed() {
  }

  @Override
  public void onReactWithAnyEmojiPageChanged(int page) {
  }

  @Override
  public void onReactWithAnyEmojiSelected(@NonNull String emoji) {
    NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().getPrimaryNavigationFragment();
    Fragment        activeFragment  = navHostFragment.getChildFragmentManager().getPrimaryNavigationFragment();

    if (activeFragment instanceof EmojiController) {
      ((EmojiController) activeFragment).onEmojiSelected(emoji);
    }
  }

  interface EmojiController {
    void onEmojiSelected(@NonNull String emoji);
  }
}
