package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.EpisodeShortName;
import com.wuming.android.tv.bean.VodShortName;
import com.wuming.android.tv.databinding.ActivityKeepBinding;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.library.PlaylistEpisodeSelection;
import com.wuming.android.tv.library.PlaylistStore;
import com.wuming.android.tv.model.SiteViewModel;
import com.wuming.android.tv.ui.adapter.PlaylistAdapter;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.custom.GridFocusRestorer;
import com.wuming.android.tv.ui.custom.PosterGridSetup;
import com.wuming.android.tv.ui.dialog.LeanbackActionDialog;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class PlaylistActivity extends BaseActivity implements PlaylistAdapter.Listener {

    private static final String STATE_FOCUS_PREFIX = "playlistFocus";

    private ActivityKeepBinding binding;
    private PlaylistAdapter adapter;
    private SiteViewModel viewModel;
    private History pendingEpisodeEdit;
    private GridFocusRestorer.ItemFocus restoredFocus;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, PlaylistActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        restoredFocus = GridFocusRestorer.readItemFocus(savedInstanceState, STATE_FOCUS_PREFIX);
        PosterGridSetup.configure(binding.recycler, adapter = new PlaylistAdapter(this));
        viewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        viewModel.getResult().observe(this, this::onEpisodeDetail);
        refresh();
    }

    private void refresh() {
        refresh(null);
    }

    private void refresh(GridFocusRestorer.ItemFocus preferredFocus) {
        GridFocusRestorer.ItemFocus focus = preferredFocus != null ? preferredFocus :
                restoredFocus != null ? restoredFocus :
                GridFocusRestorer.captureItem(binding.recycler, position -> adapter.getItem(position).getKey());
        restoredFocus = null;
        adapter.setItems(PlaylistStore.get().items(),
                () -> {
                    binding.progressLayout.showContent(true, adapter.getItemCount());
                    GridFocusRestorer.restoreItemAfterLayout(
                            binding.recycler, focus, position -> adapter.getItem(position).getKey());
                });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        GridFocusRestorer.saveItemFocus(outState, STATE_FOCUS_PREFIX,
                GridFocusRestorer.captureItem(
                        binding.recycler, position -> adapter.getItem(position).getKey()));
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onClick(History item) {
        Config config = Config.find(item.getCid());
        if (config == null) CollectActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else open(item);
    }

    private void loadConfig(Config config, History item) {
        VodConfig.load(config, lifecycleCallback(new Callback() {
            @Override public void success() { open(item); }
            @Override public void error(String msg) { Notify.show(msg); }
        }));
    }

    private void open(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    private void playFrom(History item) {
        PlaylistStore.get().start(item);
        Config config = Config.find(item.getCid());
        if (config == null) {
            PlaylistStore.get().pause();
            Notify.show(R.string.playlist_source_unavailable);
        } else if (item.getCid() != VodConfig.getCid()) {
            VodConfig.load(config, lifecycleCallback(new Callback() {
                @Override public void success() { VideoActivity.startPlaylist(getActivity(), item); }
                @Override public void error(String msg) { PlaylistStore.get().pause(); Notify.show(msg); }
            }));
        } else {
            VideoActivity.startPlaylist(this, item);
        }
    }

    @Override
    public boolean onLongClick(History item) {
        GridFocusRestorer.ItemFocus focus = GridFocusRestorer.captureItem(
                binding.recycler, position -> adapter.getItem(position).getKey());
        int actions = PlaylistStore.get().isLoop() ? R.array.playlist_actions_loop_on : R.array.playlist_actions_loop_off;
        LeanbackActionDialog.show(this, VodShortName.lookup(item.getVodName()), actions, (dialog, which) -> {
                    if (which == 0) { playFrom(item); return; }
                    else if (which == 1) { editEpisodes(item); return; }
                    else if (which == 2) { PlaylistStore.get().pause(); Notify.show(R.string.playlist_paused); return; }
                    else if (which == 3) { boolean loop = PlaylistStore.get().toggleLoop(); Notify.show(loop ? R.string.playlist_loop_on : R.string.playlist_loop_off); return; }
                    else if (which == 4) PlaylistStore.get().move(item, -1);
                    else if (which == 5) PlaylistStore.get().move(item, 1);
                    else if (which == 6) PlaylistStore.get().remove(item);
                    else if (which == 7) { confirmClear(); return; }
                    refresh(focus);
                });
        return true;
    }

    private void editEpisodes(History item) {
        Config config = Config.find(item.getCid());
        if (config == null) {
            Notify.show(R.string.playlist_source_unavailable);
        } else if (item.getCid() != VodConfig.getCid()) {
            VodConfig.load(config, lifecycleCallback(new Callback() {
                @Override public void success() { loadEpisodeDetails(item); }
                @Override public void error(String msg) { Notify.show(msg); }
            }));
        } else {
            loadEpisodeDetails(item);
        }
    }

    private void loadEpisodeDetails(History item) {
        pendingEpisodeEdit = item;
        Notify.show(R.string.playlist_loading_episodes);
        viewModel.detailContent(item.getSiteKey(), item.getVodId());
    }

    private void onEpisodeDetail(Result result) {
        History item = pendingEpisodeEdit;
        if (item == null || result == null) return;
        pendingEpisodeEdit = null;
        if (result.hasMsg()) {
            Notify.show(result.getMsg());
            return;
        }
        List<Flag> flags = result.getVod().getFlags();
        if (flags.isEmpty()) {
            Notify.show(R.string.playlist_no_episodes);
            return;
        }
        PlaylistEpisodeSelection saved = PlaylistStore.get().selection(item);
        String preferred = saved == null ? item.getVodFlag() : saved.getFlag();
        Flag selected = flags.stream().filter(flag -> flag.getFlag().equals(preferred)).findFirst().orElse(flags.get(0));
        if (flags.size() == 1) {
            showEpisodeMode(item, selected);
            return;
        }
        String[] names = flags.stream().map(Flag::getShow).toArray(String[]::new);
        AlertDialog dialog = new TvAlertDialogBuilder(this)
                .setTitle(R.string.playlist_choose_line)
                .setItems(names, (ignoredDialog, which) -> showEpisodeMode(item, flags.get(which)))
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
        showFocusedDialog(dialog);
    }

    private void showEpisodeMode(History item, Flag flag) {
        List<Episode> episodes = flag.getEpisodes();
        if (episodes.isEmpty()) {
            Notify.show(R.string.playlist_no_episodes);
            return;
        }
        AlertDialog dialog = new TvAlertDialogBuilder(this)
                .setTitle(VodShortName.lookup(item.getVodName()))
                .setItems(R.array.playlist_episode_modes, (ignoredDialog, which) -> {
                    if (which == 0) {
                        PlaylistStore.get().useAllEpisodes(item);
                        selectionSaved(item);
                    } else if (which == 1) {
                        int start = currentEpisodeIndex(item, episodes);
                        PlaylistStore.get().saveEpisodeSelection(item, flag.getFlag(),
                                new ArrayList<>(episodes.subList(start, episodes.size())), episodes.size());
                        selectionSaved(item);
                    } else {
                        showCustomEpisodeSelection(item, flag);
                    }
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
        showFocusedDialog(dialog);
    }

    private int currentEpisodeIndex(History item, List<Episode> episodes) {
        Episode current = item.getEpisode();
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).matchesName(current)) return i;
        return 0;
    }

    private void showCustomEpisodeSelection(History item, Flag flag) {
        List<Episode> episodes = flag.getEpisodes();
        PlaylistEpisodeSelection saved = PlaylistStore.get().selection(item);
        boolean custom = saved != null && saved.getFlag().equals(flag.getFlag());
        boolean[] checked = new boolean[episodes.size()];
        String[] names = new String[episodes.size()];
        for (int i = 0; i < episodes.size(); i++) {
            Episode episode = episodes.get(i);
            names[i] = EpisodeShortName.shorten(episode.getName(), item.getVodName());
            checked[i] = !custom || saved.includes(flag.getFlag(), episode);
        }
        ArrayAdapter<String> episodeAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_multiple_choice, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView text = (TextView) super.getView(position, convertView, parent);
                boolean selected = checked[position];
                text.setText((selected ? "✓  " : "○  ") + names[position]);
                text.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
                text.setAlpha(selected ? 1f : 0.62f);
                text.setContentDescription((selected ? getString(R.string.playlist_selected) :
                        getString(R.string.playlist_not_selected)) + ", " + names[position]);
                return text;
            }
        };
        AlertDialog dialog = new TvAlertDialogBuilder(this)
                .setTitle(selectionTitle(count(checked), checked.length))
                .setMultiChoiceItems(names, checked, null)
                .setNeutralButton(R.string.playlist_all_clear, null)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getListView().setAdapter(episodeAdapter);
            dialog.getListView().setChoiceMode(android.widget.ListView.CHOICE_MODE_MULTIPLE);
            for (int i = 0; i < checked.length; i++) dialog.getListView().setItemChecked(i, checked[i]);
            dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
                checked[position] = !checked[position];
                dialog.getListView().setItemChecked(position, checked[position]);
                episodeAdapter.notifyDataSetChanged();
                dialog.setTitle(selectionTitle(count(checked), checked.length));
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                boolean selectAll = count(checked) != checked.length;
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = selectAll;
                    dialog.getListView().setItemChecked(i, selectAll);
                }
                episodeAdapter.notifyDataSetChanged();
                dialog.setTitle(selectionTitle(count(checked), checked.length));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                List<Episode> selected = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(episodes.get(i));
                if (selected.isEmpty()) {
                    Notify.show(R.string.playlist_select_one_episode);
                    return;
                }
                PlaylistStore.get().saveEpisodeSelection(item, flag.getFlag(), selected, episodes.size());
                dialog.dismiss();
                selectionSaved(item);
            });
            styleList(dialog.getListView());
        });
        dialog.show();
    }

    private void showFocusedDialog(AlertDialog dialog) {
        dialog.setOnShowListener(ignored -> styleList(dialog.getListView()));
        dialog.show();
    }

    private void styleList(ListView list) {
        if (list == null) return;
        list.setSelector(R.drawable.selector_video_rank_dialog_item);
        list.setDrawSelectorOnTop(false);
        list.setVerticalScrollBarEnabled(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        if (list.getCount() > 0 && list.getSelectedItemPosition() == AdapterView.INVALID_POSITION) {
            list.setSelection(0);
        }
        list.requestFocus();
    }

    private int count(boolean[] checked) {
        int count = 0;
        for (boolean value : checked) if (value) count++;
        return count;
    }

    private String selectionTitle(int selected, int total) {
        return getString(R.string.playlist_episode_selected, selected, total);
    }

    private void selectionSaved(History item) {
        Notify.show(R.string.playlist_episode_selection_saved);
        refresh(new GridFocusRestorer.ItemFocus(item.getKey(), adapter.getItems().indexOf(item)));
    }

    private void confirmClear() {
        new TvAlertDialogBuilder(this).setMessage(R.string.playlist_clear_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    PlaylistStore.get().clear();
                    refresh();
                }).show();
    }
}
