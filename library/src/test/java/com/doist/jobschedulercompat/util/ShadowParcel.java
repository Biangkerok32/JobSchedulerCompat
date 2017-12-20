package com.doist.jobschedulercompat.util;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

import android.os.IBinder;
import android.os.Parcel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ShadowParcel doesn't correctly handle {@link Parcel#writeStrongBinder(IBinder)} / {@link Parcel#readStrongBinder()}.
 * This shims a simple implementation that uses an in-memory map to read and write Binder objects.
 */
@Implements(Parcel.class)
public class ShadowParcel extends org.robolectric.shadows.ShadowParcel {
    @RealObject
    private Parcel realObject;

    // Map each IBinder to an integer, and use the super's int-writing capability to fake Binder read/writes.
    private final AtomicInteger nextBinderId = new AtomicInteger(1);
    private final Map<Integer, IBinder> binderMap = Collections.synchronizedMap(new HashMap<Integer, IBinder>());

    @Implementation
    public void writeStrongBinder(IBinder binder) {
        int id = nextBinderId.getAndIncrement();
        binderMap.put(id, binder);
        realObject.writeInt(id);
    }

    @Implementation
    public IBinder readStrongBinder() {
        return binderMap.get(realObject.readInt());
    }
}
