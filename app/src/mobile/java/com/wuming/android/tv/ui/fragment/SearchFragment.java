package com.wuming.android.tv.ui.fragment;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.AppSettings;

import static androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN;

import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Word;
import com.wuming.android.tv.databinding.FragmentSearchBinding;
import com.wuming.android.tv.search.SearchWordClient;
import com.wuming.android.tv.ui.adapter.RecordAdapter;
import com.wuming.android.tv.ui.adapter.WordAdapter;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.custom.CustomTextListener;
import com.wuming.android.tv.ui.dialog.MobileSiteDialog;

import com.wuming.android.tv.utils.Util;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SearchFragment extends BaseFragment implements MenuProvider, WordAdapter.OnClickListener, RecordAdapter.OnClickListener {

    private FragmentSearchBinding mBinding;
    private RecordAdapter mRecordAdapter;
    private WordAdapter mWordAdapter;
    private Runnable mRecordUpdate;
    private final SearchWordClient wordClient = new SearchWordClient();

    public static SearchFragment newInstance(String keyword) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        SearchFragment fragment = new SearchFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return getArguments().getString("keyword");
    }

    private boolean empty() {
        return mBinding.keyword.getText().toString().trim().isEmpty();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void releaseBinding() {
        if (mRecordUpdate != null) removeUi(mRecordUpdate);
        mRecordUpdate = null;
        if (mBinding != null) {
            mBinding.wordRecycler.setAdapter(null);
            mBinding.recordRecycler.setAdapter(null);
        }
        mBinding = null;
        mWordAdapter = null;
        mRecordAdapter = null;
    }

    @Override
    protected void initMenu() {
        if (isHidden()) return;
        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        activity.setSupportActionBar(mBinding.toolbar);
        activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        activity.addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        activity.setTitle("");
    }

    @Override
    protected void initView() {
        setRecyclerView();
        checkKeyword();
        search();
    }

    private void setRecyclerView() {
        mBinding.wordRecycler.setHasFixedSize(false);
        mBinding.wordRecycler.setAdapter(mWordAdapter = new WordAdapter(this));
        mBinding.wordRecycler.setLayoutManager(new FlexboxLayoutManager(getContext(), FlexDirection.ROW));
        mBinding.recordRecycler.setHasFixedSize(false);
        mBinding.recordRecycler.setAdapter(mRecordAdapter = new RecordAdapter(this));
        mBinding.recordRecycler.setLayoutManager(new FlexboxLayoutManager(getContext(), FlexDirection.ROW));
    }

    @Override
    protected void initEvent() {
        mBinding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) search();
            return true;
        });
        mBinding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override
            public void afterTextChanged(Editable s) {
                requireActivity().invalidateOptionsMenu();
                getWord(s.toString());
            }
        });
        getParentFragmentManager().setFragmentResultListener("result", getViewLifecycleOwner(), (requestKey, bundle) -> {
            if (bundle.getBoolean("edit", false)) Util.showKeyboard(mBinding.keyword);
        });
    }

    private void checkKeyword() {
        setKeyword(getKeyword());
        getWord(getKeyword());
    }

    private void setKeyword(String text) {
        mBinding.keyword.setText(text);
        mBinding.keyword.setSelection(text.length());
    }

    private void search() {
        if (empty()) return;
        String keyword = mBinding.keyword.getText().toString().trim();
        if (mRecordUpdate != null) removeUi(mRecordUpdate);
        mRecordUpdate = () -> {
            mRecordUpdate = null;
            if (mRecordAdapter != null) mRecordAdapter.add(keyword);
        };
        postUi(mRecordUpdate, 250);
        Util.hideKeyboard(mBinding.keyword);
        collect(keyword);
    }

    private void collect(String keyword) {
        FragmentManager fm = requireActivity().getSupportFragmentManager();
        String collectTag = MobileCollectFragment.class.getSimpleName();
        if (fm.findFragmentByTag(collectTag) != null) return;
        String searchTag = SearchFragment.class.getSimpleName();
        FragmentTransaction ft = fm.beginTransaction().setTransition(TRANSIT_FRAGMENT_OPEN);
        ft.add(R.id.container, MobileCollectFragment.newInstance(keyword), collectTag);
        Optional.ofNullable(fm.findFragmentByTag(searchTag)).ifPresent(ft::hide);
        ft.setReorderingAllowed(true).addToBackStack(null).commit();
    }

    private void getWord(String text) {
        if (text.isEmpty()) getHot();
        else getSuggest(text);
    }

    private void getHot() {
        mBinding.word.setText(R.string.search_hot);
        mWordAdapter.setItems(Word.objectFrom(AppSettings.getHot()).getData());
        wordClient.requestHot(result -> setWordAdapter(result, true), this::setTencentHot);
    }

    private void setTencentHot(String result) {
        if (BeanUtils.isEmpty(result) || mBinding == null) return;
        try {
            List<Word.Data> hots = new ArrayList<>();
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            JsonArray itemList = json.getAsJsonObject("data").getAsJsonObject("mapResult").getAsJsonObject("0").getAsJsonArray("listInfo");
            for (JsonElement ele : itemList) {
                JsonObject obj = ele.getAsJsonObject();
                String title = obj.get("title").getAsString().trim().replaceAll("[<>《》\\-]", "").split("\\s+")[0];
                hots.add(new Word.Data(title));
            }
            if (!hots.isEmpty() && mWordAdapter.getItemCount() == 0) mWordAdapter.setItems(hots);
        } catch (Exception ignored) {
        }
    }

    private void getSuggest(String text) {
        mBinding.word.setText(R.string.search_suggest);
        wordClient.requestSuggestion(text, result -> setWordAdapter(result, false));
    }

    private void setWordAdapter(String result, boolean save) {
        if (mBinding == null) return;
        if (!save && mBinding.keyword.getText().toString().trim().isEmpty()) return;
        mWordAdapter.setItems(Word.objectFrom(result).getData());
        if (save) AppSettings.putHot(result);
    }

    private void onReset() {
        mBinding.keyword.setText("");
        requireActivity().invalidateOptionsMenu();
    }

    private void onSite() {
        Util.hideKeyboard(mBinding.keyword);
        postUi(() -> MobileSiteDialog.create(this).search().show());
    }

    private void toggleStrictSearch() {
        DisplaySettings.putStrictSearch(!DisplaySettings.isStrictSearch());
        requireActivity().invalidateOptionsMenu();
    }

    @Override
    public void onItemClick(String text) {
        setKeyword(text);
        search();
    }

    @Override
    public void onDataChanged(int size) {
        if (mBinding == null) return;
        mBinding.record.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        mBinding.recordRecycler.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        postUi(() -> {
            if (mBinding != null) mBinding.recordRecycler.requestLayout();
        }, 250);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.menu_search, menu);
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        menu.findItem(R.id.action_reset).setVisible(!empty());
        MenuItem strict = menu.findItem(R.id.action_strict_search);
        boolean enabled = DisplaySettings.isStrictSearch();
        strict.setChecked(enabled);
        strict.setIcon(enabled ? R.drawable.ic_search_strict_on : R.drawable.ic_search_strict_off);
        strict.setTitle(enabled ? R.string.search_strict_on : R.string.search_strict_off);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) requireActivity().getOnBackPressedDispatcher().onBackPressed();
        if (menuItem.getItemId() == R.id.action_reset) onReset();
        if (menuItem.getItemId() == R.id.action_strict_search) toggleStrictSearch();
        if (menuItem.getItemId() == R.id.action_site) onSite();
        return true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) requireActivity().removeMenuProvider(this);
        else initMenu();
    }

    @Override
    public void onDestroyView() {
        wordClient.cancel();
        requireActivity().removeMenuProvider(this);
        super.onDestroyView();
    }

}
