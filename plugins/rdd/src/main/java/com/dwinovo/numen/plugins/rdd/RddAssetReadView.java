package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.*;
import java.util.*;

/** 2026-11-11 B2: snapshot-only catalog/detail; no refresh, inference or new asset identity.
 * Bound the FINAL TaskResult, not just nested body (historical packet disconnect).
 */
final class RddAssetReadView {
    static final int MAX_CHARS = 8000;
    private static final Gson GSON = new Gson();
    private RddAssetReadView() {}
    static String dispatch(JsonObject args, AssetRegistry registry) {
        try {
            String action = args == null || !args.has("action") ? "legacy" : string(args,"action");
            var all = new ArrayList<>(RddAssetContext.worldAssets(registry));
            all.sort(Comparator.comparingLong((AssetRegistry.AssetEntry e)->e.observation().observedAt()).reversed()
                    .thenComparing(AssetRegistry.AssetEntry::assetId));
            if ("detail".equals(action)) {
                String id = string(args,"asset_id");
                var entry = registry == null ? null : registry.get(id).orElse(null);
                if (!RddAssetStore.isWorldAsset(entry)) return fail("not_found",Map.of());
                String result = ok(Map.of("asset",GSON.fromJson(GSON.toJson(entry),Map.class), "identity",identity(entry)));
                return result.length() <= MAX_CHARS ? result : fail("too_large",identity(entry));
            }
            if (!List.of("catalog","legacy").contains(action)) return fail("invalid_action",Map.of());
            boolean catalog = action.equals("catalog");
            int offset = catalog ? integer(args,"offset",0,0,Integer.MAX_VALUE) : 0;
            int limit = catalog ? integer(args,"limit",20,1,40) : 40;
            List<Object> shown = new ArrayList<>();
            int next = Math.min(offset,all.size());
            while (next < all.size() && shown.size() < limit) {
                var entry = all.get(next);
                Object row = catalog ? identity(entry) : GSON.fromJson(GSON.toJson(entry),Map.class);
                shown.add(row);
                if (page(all.size(),offset,next+1,shown,catalog).length() > MAX_CHARS) {
                    shown.removeLast();
                    if (shown.isEmpty()) return fail("too_large",identity(entry));
                    break;
                }
                next++;
            }
            return page(all.size(),offset,next,shown,catalog);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return fail("invalid_arguments",Map.of());
        }
    }
    private static String page(int total,int offset,int next,List<Object> rows,boolean catalog) {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("count",total); data.put("shown",rows.size()); data.put("offset",offset);
        data.put("next_offset",next<total?next:-1); data.put("truncated",next<total);
        data.put("assets",rows);data.put("view",catalog?"catalog":"legacy");
        data.put("note","Historical observations, not proof of current availability. Pages may change if the registry changes.");
        return ok(data);
    }
    private static Map<String,Object> identity(AssetRegistry.AssetEntry entry) {
        var o=entry.observation();var v=o.value();Map<String,Object> row=new LinkedHashMap<>();
        row.put("asset_id",entry.assetId());row.put("kind",fact(v,"kind"));row.put("label",fact(v,"label"));
        row.put("dimension",fact(v,"dimension"));row.put("status",entry.status().name());
        row.put("observed_at",o.observedAt()>0?o.observedAt():"UNKNOWN");
        row.put("world_id",fact(v,"world_id"));row.put("server_session",fact(v,"server_session"));
        return row;
    }
    private static Object fact(Map<String,Object> value,String key) {
        Object v=value.get(key);return v==null || v.toString().isBlank()?"UNKNOWN":v;
    }
    private static String string(JsonObject args,String key) {
        if(args==null || !args.has(key) || !args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key);
        return args.get(key).getAsString();
    }
    private static int integer(JsonObject args,String key,int fallback,int min,int max) {
        if(args==null || !args.has(key))return fallback;
        var v=args.get(key);
        if(!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(key);
        int n;try{n=v.getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException(key);}
        if(n<min||n>max)throw new IllegalArgumentException(key);return n;
    }
    private static String ok(Map<String,Object> data){return TaskResult.ok("RDD reusable world assets",data).toJson();}
    private static String fail(String code,Map<String,Object> identity){
        Map<String,Object> data=new LinkedHashMap<>(identity);data.put("code",code);
        String json=TaskResult.fail(code,data).toJson();
        // Unbounded existing IDs/labels may themselves be oversized: omit whole fields, never slice JSON.
        if(json.length()>MAX_CHARS) {
            Map<String,Object> minimal=new LinkedHashMap<>();minimal.put("code",code);
            if(identity.containsKey("asset_id")) minimal.put("asset_id",identity.get("asset_id"));
            json=TaskResult.fail(code,minimal).toJson();
            if(json.length()>MAX_CHARS)json=TaskResult.fail(code,Map.of("code",code,"identity","too_large")).toJson();
        }
        return json;
    }
}
