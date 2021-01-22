package org.thoughtcrime.securesms.sharing;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.DefaultValueLiveData;
import org.thoughtcrime.securesms.util.MappingModel;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ShareViewModel extends ViewModel {

  private static final String TAG = Log.tag(ShareViewModel.class);

  private final Context                              context;
  private final ShareRepository                      shareRepository;
  private final MutableLiveData<Optional<ShareData>> shareData;
  private final MutableLiveData<Set<ShareContact>>   selectedContacts;

  private boolean mediaUsed;
  private boolean externalShare;

  private ShareViewModel() {
    this.context          = ApplicationDependencies.getApplication();
    this.shareRepository  = new ShareRepository();
    this.shareData        = new MutableLiveData<>();
    this.selectedContacts = new DefaultValueLiveData<>(Collections.emptySet());
  }

  void onSingleMediaShared(@NonNull Uri uri, @Nullable String mimeType) {
    externalShare = true;
    shareRepository.getResolved(uri, mimeType, shareData::postValue);
  }

  void onMultipleMediaShared(@NonNull List<Uri> uris) {
    externalShare = true;
    shareRepository.getResolved(uris, shareData::postValue);
  }

  boolean isMultiShare() {
    return selectedContacts.getValue().size() > 1;
  }

  boolean onContactSelected(@NonNull ShareContact selectedContact) {
    Set<ShareContact> contacts = new LinkedHashSet<>(selectedContacts.getValue());
    if (contacts.add(selectedContact)) {
      selectedContacts.setValue(contacts);
      return true;
    } else {
      return false;
    }
  }

  void onContactDeselected(@NonNull ShareContact selectedContact) {
    Set<ShareContact> contacts = new LinkedHashSet<>(selectedContacts.getValue());
    if (contacts.remove(selectedContact)) {
      selectedContacts.setValue(contacts);
    }
  }

  @NonNull Set<ShareContact> getShareContacts() {
    Set<ShareContact> contacts = selectedContacts.getValue();
    if (contacts == null) {
      return Collections.emptySet();
    } else {
      return contacts;
    }
  }

  @NonNull LiveData<List<MappingModel<?>>> getSelectedContactModels() {
    return Transformations.map(selectedContacts, set -> Stream.of(set)
                                                              .<MappingModel<?>>mapIndexed((i, c) -> new ShareSelectionMappingModel(c, i == set.size() - 1))
                                                              .toList());
  }

  void onNonExternalShare() {
    externalShare = false;
  }

  public void onSuccessulShare() {
    mediaUsed = true;
  }

  @NonNull LiveData<Optional<ShareData>> getShareData() {
    return shareData;
  }

  boolean isExternalShare() {
    return externalShare;
  }

  @Override
  protected void onCleared() {
    ShareData data = shareData.getValue() != null ? shareData.getValue().orNull() : null;

    if (data != null && data.isExternal()  && data.isForIntent() && !mediaUsed) {
      Log.i(TAG, "Clearing out unused data.");
      BlobProvider.getInstance().delete(context, data.getUri());
    }
  }

  public static class Factory extends ViewModelProvider.NewInstanceFactory {
    @Override
    public @NonNull<T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection ConstantConditions
      return modelClass.cast(new ShareViewModel());
    }
  }
}
