/*
 * Copyright (C) 2014-2017 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.securesms.sharing;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.util.Consumer;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.ContactSelectionListFragment;
import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.SearchToolbar;
import org.thoughtcrime.securesms.contacts.ContactsCursorLoader.DisplayMode;
import org.thoughtcrime.securesms.conversation.ConversationIntents;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.mediasend.MediaSendActivity;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.sharing.interstitial.ShareInterstitialActivity;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point for sharing content into the app.
 *
 * Handles contact selection when necessary, but also serves as an entry point for when the contact
 * is known (such as choosing someone in a direct share).
 */
public class ShareActivity extends PassphraseRequiredActivity
    implements ContactSelectionListFragment.OnContactSelectedListener,
    ContactSelectionListFragment.OnSelectionLimitReachedListener
{
  private static final String TAG = ShareActivity.class.getSimpleName();

  private static final short RESULT_TEXT_CONFIRMATION  = 1;
  private static final short RESULT_MEDIA_CONFIRMATION = 2;

  public static final String EXTRA_THREAD_ID          = "thread_id";
  public static final String EXTRA_RECIPIENT_ID       = "recipient_id";
  public static final String EXTRA_DISTRIBUTION_TYPE  = "distribution_type";

  private final DynamicTheme    dynamicTheme    = new DynamicNoActionBarTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private ConstraintLayout             shareContainer;
  private ContactSelectionListFragment contactsFragment;
  private SearchToolbar                searchToolbar;
  private ImageView                    searchAction;
  private View                         shareConfirm;
  private ShareSelectionAdapter        adapter;

  private ShareViewModel viewModel;

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle icicle, boolean ready) {
    setContentView(R.layout.share_activity);

    initializeViewModel();
    initializeMedia();
    initializeIntent();
    initializeToolbar();
    initializeResources();
    initializeSearch();
    handleDestination();
  }

  @Override
  public void onResume() {
    Log.i(TAG, "onResume()");
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  public void onStop() {
    super.onStop();

    if (!isFinishing() && !viewModel.isMultiShare()) {
      finish();
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      onBackPressed();
      return true;
    } else {
      return super.onOptionsItemSelected(item);
    }
  }

  @Override
  public void onBackPressed() {
    if (searchToolbar.isVisible()) searchToolbar.collapse();
    else                           super.onBackPressed();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    if (resultCode == RESULT_OK) {
      switch (requestCode) {
        case RESULT_MEDIA_CONFIRMATION:
        case RESULT_TEXT_CONFIRMATION:
          viewModel.onSuccessulShare();
          finish();
          break;
        default:
          super.onActivityResult(requestCode, resultCode, data);
      }
    } else {
      shareConfirm.setClickable(true);
      super.onActivityResult(requestCode, resultCode, data);
    }
  }

  @Override
  public boolean onBeforeContactSelected(Optional<RecipientId> recipientId, String number) {
    return viewModel.onContactSelected(new ShareContact(recipientId, number));
  }

  @Override
  public void onContactDeselected(@NonNull Optional<RecipientId> recipientId, String number) {
    viewModel.onContactDeselected(new ShareContact(recipientId, number));
  }

  private void animateInSelection() {
    TransitionManager.endTransitions(shareContainer);
    TransitionManager.beginDelayedTransition(shareContainer);

    ConstraintSet constraintSet = new ConstraintSet();
    constraintSet.clone(shareContainer);
    constraintSet.setVisibility(R.id.selection_group, ConstraintSet.VISIBLE);
    constraintSet.applyTo(shareContainer);
  }

  private void animateOutSelection() {
    TransitionManager.endTransitions(shareContainer);
    TransitionManager.beginDelayedTransition(shareContainer);

    ConstraintSet constraintSet = new ConstraintSet();
    constraintSet.clone(shareContainer);
    constraintSet.setVisibility(R.id.selection_group, ConstraintSet.GONE);
    constraintSet.applyTo(shareContainer);
  }

  private void initializeIntent() {
    if (!getIntent().hasExtra(ContactSelectionListFragment.DISPLAY_MODE)) {
      int mode = DisplayMode.FLAG_PUSH | DisplayMode.FLAG_ACTIVE_GROUPS | DisplayMode.FLAG_SELF;

      if (TextSecurePreferences.isSmsEnabled(this) && viewModel.isExternalShare())  {
        mode |= DisplayMode.FLAG_SMS;
      }

      if (FeatureFlags.groupsV1ForcedMigration()) {
        mode |= DisplayMode.FLAG_HIDE_GROUPS_V1;
      }

      getIntent().putExtra(ContactSelectionListFragment.DISPLAY_MODE, mode);
    }

    getIntent().putExtra(ContactSelectionListFragment.REFRESHABLE, false);
    getIntent().putExtra(ContactSelectionListFragment.RECENTS, true);
    getIntent().putExtra(ContactSelectionListFragment.SELECTION_LIMITS, FeatureFlags.shareSelectionLimit());
    getIntent().putExtra(ContactSelectionListFragment.HIDE_COUNT, true);
    getIntent().putExtra(ContactSelectionListFragment.DISPLAY_CHIPS, false);
    getIntent().putExtra(ContactSelectionListFragment.CAN_SELECT_SELF, true);
  }

  private void initializeToolbar() {
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    ActionBar actionBar = getSupportActionBar();

    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
    }
  }

  private void initializeResources() {
    searchToolbar    = findViewById(R.id.search_toolbar);
    searchAction     = findViewById(R.id.search_action);
    shareConfirm     = findViewById(R.id.share_confirm);
    shareContainer   = findViewById(R.id.container);
    contactsFragment = new ContactSelectionListFragment();
    adapter          = new ShareSelectionAdapter();

    RecyclerView contactsRecycler = findViewById(R.id.selected_list);
    contactsRecycler.setAdapter(adapter);

    getSupportFragmentManager().beginTransaction()
                               .replace(R.id.contact_selection_list_fragment, contactsFragment)
                               .commit();

    shareConfirm.setOnClickListener(unused -> {
      Set<ShareContact> shareContacts = viewModel.getShareContacts();

      if (shareContacts.isEmpty())        throw new AssertionError();
      else if (shareContacts.size() == 1) onConfirmSingleDestination(shareContacts.iterator().next());
      else                                onConfirmMultipleDestinations(shareContacts);
    });

    viewModel.getSelectedContactModels().observe(this, models -> {
      adapter.submitList(models, () -> contactsRecycler.scrollToPosition(models.size() - 1));

      shareConfirm.setEnabled(!models.isEmpty());
      shareConfirm.setAlpha(models.isEmpty() ? 0.5f : 1f);
      if (models.isEmpty()) {
        animateOutSelection();
      } else {
        animateInSelection();
      }
    });
  }

  private void initializeViewModel() {
    this.viewModel = ViewModelProviders.of(this, new ShareViewModel.Factory()).get(ShareViewModel.class);
  }

  private void initializeSearch() {
    //noinspection IntegerDivisionInFloatingPointContext
    searchAction.setOnClickListener(v -> searchToolbar.display(searchAction.getX() + (searchAction.getWidth() / 2),
                                                               searchAction.getY() + (searchAction.getHeight() / 2)));

    searchToolbar.setListener(new SearchToolbar.SearchListener() {
      @Override
      public void onSearchTextChange(String text) {
        if (contactsFragment != null) {
          contactsFragment.setQueryFilter(text);
        }
      }

      @Override
      public void onSearchClosed() {
        if (contactsFragment != null) {
          contactsFragment.resetQueryFilter();
        }
      }
    });
  }

  private void initializeMedia() {
    if (Intent.ACTION_SEND_MULTIPLE.equals(getIntent().getAction())) {
      Log.i(TAG, "Multiple media share.");
      List<Uri> uris = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);

      viewModel.onMultipleMediaShared(uris);
    } else if (Intent.ACTION_SEND.equals(getIntent().getAction()) || getIntent().hasExtra(Intent.EXTRA_STREAM)) {
      Log.i(TAG, "Single media share.");
      Uri    uri  = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
      String type = getIntent().getType();

      viewModel.onSingleMediaShared(uri, type);
    } else {
      Log.i(TAG, "Internal media share.");
      viewModel.onNonExternalShare();
    }
  }

  private void handleDestination() {
    Intent      intent           = getIntent();
    long        threadId         = intent.getLongExtra(EXTRA_THREAD_ID, -1);
    int         distributionType = intent.getIntExtra(EXTRA_DISTRIBUTION_TYPE, -1);
    RecipientId recipientId      = null;

    if (intent.hasExtra(EXTRA_RECIPIENT_ID)) {
      recipientId = RecipientId.from(intent.getStringExtra(EXTRA_RECIPIENT_ID));
    }

    boolean hasPreexistingDestination = threadId != -1 && recipientId != null && distributionType != -1;

    if (hasPreexistingDestination) {
      if (contactsFragment.getView() != null) {
        contactsFragment.getView().setVisibility(View.GONE);
      }
      onSingleDestinationChosen(threadId, recipientId);
    } else if (viewModel.isExternalShare()) {
      validateAvailableRecipients();
    }
  }

  private void onConfirmSingleDestination(@NonNull ShareContact shareContact) {
    shareConfirm.setClickable(false);
    SimpleTask.run(this.getLifecycle(),
                   () -> resolveShareContact(shareContact),
                   result -> onSingleDestinationChosen(result.getThreadId(), result.getRecipientId()));
  }

  private void onConfirmMultipleDestinations(@NonNull Set<ShareContact> shareContacts) {
    shareConfirm.setClickable(false);
    SimpleTask.run(this.getLifecycle(),
                   () -> resolvedShareContacts(shareContacts),
                   this::onMultipleDestinationsChosen);
  }

  private Set<ShareContactAndThread> resolvedShareContacts(@NonNull Set<ShareContact> sharedContacts) {
    Set<Recipient> recipients = Stream.of(sharedContacts)
                                      .map(contact -> contact.getRecipientId()
                                                             .transform(Recipient::resolved)
                                                             .or(() -> Recipient.external(this, contact.getNumber())))
                                      .collect(Collectors.toSet());

    Map<RecipientId, Long> existingThreads = DatabaseFactory.getThreadDatabase(this)
                                                            .getThreadIdsIfExistsFor(Stream.of(recipients)
                                                                                           .map(Recipient::getId)
                                                                                           .toArray(RecipientId[]::new));

    return Stream.of(recipients)
                 .map(recipient -> new ShareContactAndThread(recipient.getId(), Util.getOrDefault(existingThreads, recipient.getId(), -1L), recipient.isForceSmsSelection() || !recipient.isRegistered()))
                 .collect(Collectors.toSet());
  }

  @WorkerThread
  private ShareContactAndThread resolveShareContact(@NonNull ShareContact shareContact) {
    Recipient recipient;
    if (shareContact.getRecipientId().isPresent()) {
      recipient = Recipient.resolved(shareContact.getRecipientId().get());
    } else {
      Log.i(TAG, "[onContactSelected] Maybe creating a new recipient.");
      recipient = Recipient.external(this, shareContact.getNumber());
    }

    long existingThread = DatabaseFactory.getThreadDatabase(this).getThreadIdIfExistsFor(recipient.getId());
    return new ShareContactAndThread(recipient.getId(), existingThread, recipient.isForceSmsSelection() || !recipient.isRegistered());
  }

  private void validateAvailableRecipients() {
    resolveShareData(data -> {
      int mode = getIntent().getIntExtra(ContactSelectionListFragment.DISPLAY_MODE, -1);

      if (mode == -1) return;

      mode = data.isMmsOrSmsSupported() ? mode | DisplayMode.FLAG_SMS : mode & ~DisplayMode.FLAG_SMS;
      getIntent().putExtra(ContactSelectionListFragment.DISPLAY_MODE, mode);

      contactsFragment.reset();
    });
  }

  private void resolveShareData(@NonNull Consumer<ShareData> onResolved) {
    AtomicReference<AlertDialog> progressWheel = new AtomicReference<>();

    if (viewModel.getShareData().getValue() == null) {
      progressWheel.set(SimpleProgressDialog.show(this));
    }

    viewModel.getShareData().observe(this, (data) -> {
      if (data == null) return;

      if (progressWheel.get() != null) {
        progressWheel.get().dismiss();
        progressWheel.set(null);
      }

      if (!data.isPresent()) {
        Log.w(TAG, "No data to share!");
        Toast.makeText(this, R.string.ShareActivity_multiple_attachments_are_only_supported, Toast.LENGTH_LONG).show();
        finish();
        return;
      }

      onResolved.accept(data.get());
    });
  }

  private void onMultipleDestinationsChosen(@NonNull Set<ShareContactAndThread> shareContactAndThreads) {
    if (!viewModel.isExternalShare()) {
      openInterstitial(shareContactAndThreads, null);
      return;
    }

    resolveShareData(data -> openInterstitial(shareContactAndThreads, data));
  }

  private void onSingleDestinationChosen(long threadId, @NonNull RecipientId recipientId) {
    if (!viewModel.isExternalShare()) {
      openConversation(threadId, recipientId, null);
      return;
    }

    resolveShareData(data -> openConversation(threadId, recipientId, data));
  }

  private void openConversation(long threadId, @NonNull RecipientId recipientId, @Nullable ShareData shareData) {
    ShareIntents.Args           args    = ShareIntents.Args.from(getIntent());
    ConversationIntents.Builder builder = ConversationIntents.createBuilder(this, recipientId, threadId)
                                                             .withMedia(args.getExtraMedia())
                                                             .withDraftText(args.getExtraText() != null ? args.getExtraText().toString() : null)
                                                             .withStickerLocator(args.getExtraSticker())
                                                             .asBorderless(args.isBorderless());

    if (shareData != null && shareData.isForIntent()) {
      Log.i(TAG, "Shared data is a single file.");
      builder.withDataUri(shareData.getUri())
             .withDataType(shareData.getMimeType());
    } else if (shareData != null && shareData.isForMedia()) {
      Log.i(TAG, "Shared data is set of media.");
      builder.withMedia(shareData.getMedia());
    } else if (shareData != null && shareData.isForPrimitive()) {
      Log.i(TAG, "Shared data is a primitive type.");
    } else if (shareData == null && args.getExtraSticker() != null) {
      builder.withDataType(getIntent().getType());
    } else {
      Log.i(TAG, "Shared data was not external.");
    }

    viewModel.onSuccessulShare();

    startActivity(builder.build());
  }

  private void openInterstitial(@NonNull Set<ShareContactAndThread> shareContactAndThreads, @Nullable ShareData shareData) {
    ShareIntents.Args      args    = ShareIntents.Args.from(getIntent());
    MultiShareArgs.Builder builder = new MultiShareArgs.Builder(shareContactAndThreads)
                                                       .withMedia(args.getExtraMedia())
                                                       .withDraftText(args.getExtraText() != null ? args.getExtraText().toString() : null)
                                                       .withStickerLocator(args.getExtraSticker())
                                                       .asBorderless(args.isBorderless());

    if (shareData != null && shareData.isForIntent()) {
      Log.i(TAG, "Shared data is a single file.");
      builder.withDataUri(shareData.getUri())
             .withDataType(shareData.getMimeType());
    } else if (shareData != null && shareData.isForMedia()) {
      Log.i(TAG, "Shared data is set of media.");
      builder.withMedia(shareData.getMedia());
    } else if (shareData != null && shareData.isForPrimitive()) {
      Log.i(TAG, "Shared data is a primitive type.");
    } else if (shareData == null && args.getExtraSticker() != null) {
      builder.withDataType(getIntent().getType());
    } else {
      Log.i(TAG, "Shared data was not external.");
    }

    MultiShareArgs multiShareArgs = builder.build();
    InterstitialContentType interstitialContentType = multiShareArgs.getInterstitialContentType();
    switch (interstitialContentType) {
      case TEXT:
        startActivityForResult(ShareInterstitialActivity.createIntent(this, multiShareArgs), RESULT_TEXT_CONFIRMATION);
        break;
      case MEDIA:
        List<Media> media = new ArrayList<>(multiShareArgs.getMedia());
        if (media.isEmpty()) {
          media.add(new Media(multiShareArgs.getDataUri(),
                              multiShareArgs.getDataType(),
                              0,
                              0,
                              0,
                              0,
                              0,
                              false,
                              Optional.absent(),
                              Optional.absent(),
                              Optional.absent()));
        }

        startActivityForResult(MediaSendActivity.buildShareIntent(this,
                                                                  media,
                                                                  Stream.of(multiShareArgs.getShareContactAndThreads()).map(ShareContactAndThread::getRecipientId).toList(),
                                                                  multiShareArgs.getDraftText(),
                                                                  MultiShareSender.getWorseTransportOption(this, multiShareArgs.getShareContactAndThreads())),
            RESULT_MEDIA_CONFIRMATION);
        break;
      default:
        //noinspection CodeBlock2Expr
        MultiShareSender.send(multiShareArgs, results -> {
          MultiShareDialogs.displayResultDialog(this, results, () -> {
            viewModel.onSuccessulShare();
            finish();
          });
        });
        break;
    }
  }

  @Override
  public void onSuggestedLimitReached(int limit) {
  }

  @Override
  public void onHardLimitReached(int limit) {
    MultiShareDialogs.displayMaxSelectedDialog(this, limit);
  }
}