package com.wuming.android.tv.ui.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.FilterCache;
import com.wuming.android.tv.bean.Class;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Filter;
import com.wuming.android.tv.databinding.FragmentFolderBinding;
import com.wuming.android.tv.ui.activity.VodActivity;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.utils.BundleUtil;

import java.util.HashMap;
import java.util.Optional;

public class LeanbackFolderFragment extends BaseFragment {

    private static final String TAG = "VodFolderTrace";

    private FragmentFolderBinding mBinding;
    private Class mType;

    public static LeanbackFolderFragment newInstance(String key, Class type) {
        return newInstance(key, type, null);
    }

    public static LeanbackFolderFragment newInstance(String key, Class type, Result result) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putParcelable("type", type);
        if (result != null) args.putParcelable("result", result);
        LeanbackFolderFragment fragment = new LeanbackFolderFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    public Class getType() {
        return BundleUtil.getParcelable(getArguments(), "type", Class.class);
    }

    private Result getResult() {
        return BundleUtil.getParcelable(getArguments(), "result", Result.class);
    }

    private LeanbackTypeFragment getChild() {
        return (LeanbackTypeFragment) getChildFragmentManager().findFragmentById(R.id.container);
    }

    private VodActivity getParent() {
        return (VodActivity) getActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentFolderBinding.inflate(inflater, container, false);
    }

    @Override
    protected void releaseBinding() {
        mBinding = null;
    }

    @Override
    protected void initView() {
        mType = getType();
        if (getChild() == null) {
            getChildFragmentManager().beginTransaction().replace(R.id.container,
                    LeanbackTypeFragment.newInstance(getKey(), mType.getTypeId(), mType.getStyle(),
                            getExtend(), mType.isFolder(), getResult())).commit();
        }
    }

    private HashMap<String, String> getExtend() {
        HashMap<String, String> extend = new HashMap<>();
        // Omit unspecified defaults; "" is an explicit selection (often All).
        // Sending type="" for a missing default made DouDou request /subject_collection//items.
        for (Filter filter : FilterCache.get(mType)) if (filter.getInit() != null) extend.put(filter.getKey(), filter.getInit());
        return extend;
    }

    public void openFolder(String typeId, HashMap<String, String> extend) {
        Log.d(TAG, "openFolder site=" + getKey() + " parentType=" + mType.getTypeId() + " childType=" + typeId + " categoryStyle=" + LeanbackTypeFragment.describeStyle(mType.getStyle()));
        LeanbackTypeFragment next = LeanbackTypeFragment.newInstance(getKey(), typeId, mType.getStyle(), extend, true, null);
        next.requireArguments().putBoolean("nested", true);
        FragmentTransaction ft = getChildFragmentManager().beginTransaction();
        Optional.ofNullable(getParent()).ifPresent(VodActivity::closeFilter);
        Optional.ofNullable(getChild()).ifPresent(ft::hide);
        ft.add(R.id.container, next);
        ft.addToBackStack(null);
        ft.commit();
    }

    public void toggleFilter(boolean visible) {
        Optional.ofNullable(getChild()).ifPresent(f -> f.toggleFilter(visible));
    }

    public void onRefresh() {
        Optional.ofNullable(getChild()).ifPresent(LeanbackTypeFragment::onRefresh);
    }

    public boolean canBack() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void goBack() {
        getChildFragmentManager().popBackStack();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Fragment is now visible to user
    }

    @Override
    public void onPause() {
        super.onPause();
        // Fragment is no longer visible to user
        if (mBinding != null) Optional.ofNullable(getChild()).ifPresent(f -> f.onPause());
    }
}
