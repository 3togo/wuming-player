package com.wuming.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Class;
import com.wuming.android.tv.bean.Filter;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Value;
import com.wuming.android.tv.databinding.FragmentFolderBinding;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.utils.BundleUtil;

import java.util.HashMap;
import java.util.Optional;

public class MobileFolderFragment extends BaseFragment {

    private Class mType;

    public static MobileFolderFragment newInstance(String key, Class type, int y) {
        Bundle args = new Bundle();
        args.putInt("y", y);
        args.putString("key", key);
        args.putParcelable("type", type);
        MobileFolderFragment fragment = new MobileFolderFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    public Class getType() {
        return BundleUtil.getParcelable(getArguments(), "type", Class.class);
    }

    private int getY() {
        return getArguments().getInt("y");
    }

    private VodFragment getParent() {
        return (VodFragment) getParentFragment();
    }

    private MobileTypeFragment getChild() {
        return (MobileTypeFragment) getChildFragmentManager().findFragmentById(R.id.container);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return FragmentFolderBinding.inflate(inflater, container, false);
    }

    @Override
    protected void releaseBinding() {
        // No binding field held
    }

    @Override
    protected void initView() {
        mType = getType();
        getChildFragmentManager().beginTransaction().replace(R.id.container, MobileTypeFragment.newInstance(getKey(), mType.getTypeId(), mType.getStyle(), getExtend(), mType.isFolder(), getY())).commit();
    }

    private HashMap<String, String> getExtend() {
        HashMap<String, String> extend = new HashMap<>();
        // Omit unspecified defaults; "" is an explicit selection (often All).
        // Sending type="" for a missing default made DouDou request /subject_collection//items.
        for (Filter filter : mType.getFilters()) if (filter.getInit() != null) extend.put(filter.getKey(), filter.setActivated(filter.getInit()));
        return extend;
    }

    public void openFolder(String typeId, HashMap<String, String> extend) {
        MobileTypeFragment next = MobileTypeFragment.newInstance(getKey(), typeId, mType.getStyle(), extend, mType.isFolder(), getY());
        FragmentTransaction ft = getChildFragmentManager().beginTransaction();
        Optional.ofNullable(getChild()).ifPresent(ft::hide);
        ft.add(R.id.container, next);
        ft.addToBackStack(null);
        ft.commit();
    }

    public Result getResult() {
        return getParent().getResult();
    }

    public void onRefresh() {
        Optional.ofNullable(getChild()).ifPresent(MobileTypeFragment::onRefresh);
    }

    public void scrollToTop() {
        Optional.ofNullable(getChild()).ifPresent(MobileTypeFragment::scrollToTop);
    }

    public void setFilter(String key, Value value) {
        Optional.ofNullable(getChild()).ifPresent(f -> f.setFilter(key, value));
    }

    public boolean canBack() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void goBack() {
        getChildFragmentManager().popBackStack();
    }
}
