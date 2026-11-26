package com.dwinovo.numen.plugins.worldstate.focus;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local focus leases; not an authorization or durable adoption record. */
public final class FocusPinStore {
    public static final int MAX_PINS_PER_COMPANION = 4;
    /** Technical bound: at most 64 holders per physical focus, not a product quota. */
    public static final int MAX_OWNERS_PER_PIN = 64;
    public static final long DEFAULT_TTL_MS = 120_000L, MIN_TTL_MS = 1_000L, MAX_TTL_MS = 600_000L;
    private final AtomicLong seq = new AtomicLong();
    private final Map<UUID,List<FocusPin>> pins = new LinkedHashMap<>();
    private final Map<String,Holder> holders = new LinkedHashMap<>();
    private static final class Holder {
        final String version; boolean legacy; String legacyHandle;
        final Map<String,String> owners = new LinkedHashMap<>();
        Holder(String version, boolean legacy) { this.version=version; this.legacy=legacy; }
    }
    /** Opaque handle is not a credential; trusted server host must supply owner. */
    public record Lease(String leaseId, FocusPin pin) {}

    public synchronized FocusPin pin(UUID c,String w,String s,String d,String topic,long now,long ttl,long age,int limit,int items) {
        if(c==null) return null;
        prepare(c,w,s,now);
        List<FocusPin> list=pins.computeIfAbsent(c,k->new ArrayList<>());
        for(FocusPin p:list) if(p.topic().equals(topic)&&p.dimension().equals(d) && holders.get(p.id()).legacy) return legacyView(p, holders.get(p.id()));
        // A legacy handle can share an unversioned owner focus only with identical observation parameters.
        for(FocusPin p:list) if(p.topic().equals(topic)&&p.dimension().equals(d)
                && holders.get(p.id()).version.isEmpty() && p.maxAgeMs()==age && p.limit()==limit && p.maxItems()==items) {
            Holder h=holders.get(p.id()); h.legacy=true; h.legacyHandle=newLegacyHandle(); return legacyView(p,h);
        }
        if(list.size()>=MAX_PINS_PER_COMPANION) return null;
        FocusPin p=create(c,w,s,d,topic,now,ttl,age,limit,items);
        list.add(p); Holder h=new Holder("",true); h.legacyHandle=p.id(); holders.put(p.id(),h); return p;
    }
    /** Internal building block only: no model-facing schema or learned package connection.
     * Shared holders inherit the existing physical pin expiry; acquiring never renews it.
     * Observation parameters are isolation keys only; existing projector does not yet consume them.
     * Caller must serialize world/session transitions and supply trusted owner; this store is not an auth boundary. */
    public synchronized Lease acquire(UUID c,String w,String s,String d,String topic,String version,String owner,
                                      long now,long ttl,long age,int limit,int items) {
        if(c==null || owner==null || owner.isBlank() || version==null || w==null || s==null || d==null || topic==null) return null;
        prepare(c,w,s,now);
        List<FocusPin> list=pins.computeIfAbsent(c,k->new ArrayList<>());
        FocusPin match=null;
        for(FocusPin p:list) {
            Holder h=holders.get(p.id());
            if(p.dimension().equals(d)&&p.topic().equals(topic)&&h.version.equals(version)
                    &&p.maxAgeMs()==age&&p.limit()==limit&&p.maxItems()==items) {match=p;break;}
        }
        if(match==null) {
            if(list.size()>=MAX_PINS_PER_COMPANION) return null;
            match=create(c,w,s,d,topic,now,ttl,age,limit,items); list.add(match); holders.put(match.id(),new Holder(version,false));
        }
        Holder h=holders.get(match.id());
        String lease=h.owners.get(owner);
        if(lease==null) {
            if(h.owners.size()>=MAX_OWNERS_PER_PIN) return null;
            lease=UUID.randomUUID().toString(); h.owners.put(owner,lease);
        }
        return new Lease(lease,match);
    }
    public synchronized boolean release(UUID c,String owner,String leaseId) {
        if(c==null||owner==null||leaseId==null) return false;
        List<FocusPin> list=pins.get(c); if(list==null) return false;
        for(Iterator<FocusPin> it=list.iterator();it.hasNext();) {
            FocusPin p=it.next(); Holder h=holders.get(p.id());
            if(leaseId.equals(h.owners.get(owner))) {
                h.owners.remove(owner); if(!h.legacy&&h.owners.isEmpty()){it.remove();holders.remove(p.id());}
                if(list.isEmpty()) pins.remove(c); return true;
            }
        }
        return false;
    }
    public synchronized boolean unpin(UUID c,String id) {
        if(c==null||id==null) return false;
        List<FocusPin> list=pins.get(c); if(list==null) return false;
        for(Iterator<FocusPin> it=list.iterator();it.hasNext();) {
            FocusPin p=it.next(); Holder h=holders.get(p.id());
            if(!h.legacy || !id.equals(h.legacyHandle)) continue;
            h.legacy=false; h.legacyHandle=null; if(h.owners.isEmpty()){it.remove();holders.remove(p.id());}
            if(list.isEmpty()) pins.remove(c); return true;
        }
        return false;
    }
    public synchronized List<FocusPin> active(UUID c,String w,String s,long now) {
        pruneExpired(c,now);
        return pins.getOrDefault(c,List.of()).stream().filter(p->p.worldId().equals(w)&&p.sessionId().equals(s)).toList();
    }
    private String newLegacyHandle() { return "pin-"+seq.incrementAndGet(); }
    private static FocusPin legacyView(FocusPin p,Holder h) {
        return new FocusPin(h.legacyHandle,p.companionId(),p.worldId(),p.sessionId(),p.dimension(),p.topic(),
                p.createdAtMs(),p.expiresAtMs(),p.maxAgeMs(),p.limit(),p.maxItems());
    }
    private FocusPin create(UUID c,String w,String s,String d,String topic,long now,long ttl,long age,int limit,int items) {
        long duration=Math.max(MIN_TTL_MS,Math.min(MAX_TTL_MS,ttl<=0?DEFAULT_TTL_MS:ttl));
        long expiry=now>Long.MAX_VALUE-duration?Long.MAX_VALUE:now+duration;
        return new FocusPin("pin-"+seq.incrementAndGet(),c.toString(),w,s,d,topic,now,expiry,age,limit,items);
    }
    private void prepare(UUID c,String w,String s,long now) {
        pruneExpired(c,now); List<FocusPin> list=pins.get(c); if(list==null) return;
        list.removeIf(p->{boolean remove=!p.worldId().equals(w)||!p.sessionId().equals(s);if(remove) holders.remove(p.id());return remove;});
        if(list.isEmpty()) pins.remove(c);
    }
    /** Bounded round robin; returns subjects examined, not pins removed. No thread is started. */
    public synchronized int sweepBatch(long now, int maxSubjects) { return sweepBatchStats(now,maxSubjects).checked(); }
    public record SweepStats(int checked,int removed) {}
    public synchronized SweepStats sweepBatchStats(long now, int maxSubjects) {
        int removed = 0;
        int count = Math.min(Math.max(0, maxSubjects), pins.size());
        for (int i=0; i<count; i++) {
            UUID subject = pins.keySet().iterator().next();
            int before = pins.get(subject).size();
            pruneExpired(subject, now);
            List<FocusPin> remaining = pins.remove(subject);
            removed += before - (remaining == null ? 0 : remaining.size());
            if (remaining != null) pins.put(subject, remaining);
        }
        return new SweepStats(count,removed);
    }
    /** Counts and clears atomically without pruning or identifying subjects. */
    public record ClearStats(int subjectsBefore,int pinsBefore,int subjectsAfter,int pinsAfter) {}
    public synchronized ClearStats clearWithStats() { int subjects=pins.size(), count=holders.size(); pins.clear(); holders.clear(); return new ClearStats(subjects,count,pins.size(),holders.size()); }
    public synchronized void clear() { pins.clear(); holders.clear(); }
    /** Legacy full scan; callers needing bounded work use sweepBatch. */
    public synchronized void sweepExpired(long now) {
        for(UUID c : new ArrayList<>(pins.keySet())) pruneExpired(c,now);
    }
    private void pruneExpired(UUID c,long now) {
        List<FocusPin> list=pins.get(c); if(list==null) return;
        list.removeIf(p->{boolean remove=!p.alive(now);if(remove) holders.remove(p.id());return remove;});
        if(list.isEmpty()) pins.remove(c);
    }
}
