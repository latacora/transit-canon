So we hit a really interesting edge case with Clojure's `proxy` that I want to share, because it took us a while to figure out and it's the kind of thing that could bite anyone.

*The setup:* We needed to extend Transit's JSON serializer. Transit has a `JsonEmitter` class with a `marshal` method that recursively serializes values. We wanted to intercept those `marshal` calls to handle a special case. Simple, right? Just subclass and override.

In Clojure, when you want to subclass a Java class, you typically reach for `proxy`. It's the easy, no-compilation-needed way to do it:

```clojure
(def my-emitter
  (proxy [JsonEmitter] [gen handlers]
    (marshal [obj as-map-key cache]
      (println "I see:" obj)
      (proxy-super marshal obj as-map-key cache))))
```

We wrote something like this, tested it with `(.emit my-emitter [1 2 3])`, and expected to see our override called four times—once for the vector, once for each element inside it.

Instead, we saw it called exactly *once*. Just for the top-level vector. The elements 1, 2, 3 were serialized by the parent class, completely bypassing our override.

What?

---

Let me explain what's happening. First, you need to understand what `proxy` actually generates at the JVM level.

When you create a proxy, Clojure generates a real Java class at runtime. But here's the thing—it doesn't generate a normal subclass. Instead, it generates something like this:

```java
public class proxy$JsonEmitter extends JsonEmitter {

    // This map holds {"marshal" -> your-clojure-fn, ...}
    private IPersistentMap __clojureFnMap;

    public void marshal(Object o, boolean asMapKey, WriteCache cache) {
        // Look up "marshal" in the map
        IFn fn = (IFn) __clojureFnMap.valAt("marshal");

        if (fn != null) {
            fn.invoke(this, o, asMapKey, cache);  // call your fn
        } else {
            super.marshal(o, asMapKey, cache);    // fall back to parent
        }
    }

    // Every other method follows the same pattern...
}
```

See that? Every method does a map lookup first. If it finds your function, it calls it. If the map has `nil` for that method, it falls through to the parent.

Now, why would the map ever have `nil`? That's where `proxy-super` comes in.

---

When your override calls `proxy-super`, Clojure needs to call the parent's implementation. But there's a problem. If the parent's code internally calls `this.marshal()`, that would go through the proxy's generated method, find your function in the map, call it, which calls `proxy-super`, which calls the parent, which calls `this.marshal()`... infinite loop.

So `proxy-super` does something clever. Here's the actual implementation:

```clojure
(defn proxy-call-with-super [call this meth]
  (let [m (proxy-mappings this)]
    (update-proxy this (assoc m meth nil))  ;; <-- sets "marshal" to nil!
    (try
      (call)                                 ;; calls the parent
      (finally
        (update-proxy this m)))))            ;; restores the map after
```

It temporarily sets your method's entry to `nil` before calling the parent, then restores it afterward. This prevents the infinite loop.

But here's the catch: while the parent is executing, *all* lookups for that method return `nil`. Including the recursive calls.

---

Let me draw what happens when we call `.emit` on our proxy with `[1 2 3]`:

```
(.emit my-proxy [1 2 3])
    │
    ▼
proxy$marshal is called
    │
    ├─ map.get("marshal") → finds your fn ✓
    │
    ▼
Your Clojure fn runs, calls proxy-super
    │
    ├─ map.put("marshal", nil)  ← THIS IS THE PROBLEM
    │
    ▼
Parent's marshal runs
    │
    ├─ Does some stuff...
    ├─ Calls emitArray()
    │       │
    │       ▼
    │   emitArray loops over [1, 2, 3]
    │   For each element, calls this.marshal(element)
    │       │
    │       ▼
    │   proxy$marshal is called
    │       │
    │       ├─ map.get("marshal") → nil! (still cleared!)
    │       │
    │       ▼
    │   Falls through to super.marshal
    │   YOUR OVERRIDE IS BYPASSED
    │
    ▼
proxy-super finishes, restores map
```

The map stays nil'd for the *entire duration* of the parent's execution. Any recursive calls during that time skip your override entirely.

We verified this by printing the mapping from inside different methods:

```
PROXY marshal called with: [1 2 3]
  mapping for "marshal": #object[my-fn ...]   ← present

Inside emitArray (called by parent):
  mapping for "marshal": nil                   ← GONE!

After proxy-super returns:
  mapping for "marshal": #object[my-fn ...]   ← restored, too late
```

---

You might ask: why does proxy work this way? Why not just generate a normal Java subclass?

A few reasons:

*Runtime mutability.* Proxy lets you change implementations after creation:

```clojure
(def p (proxy [Runnable] [] (run [] (println "v1"))))
(.run p)  ; "v1"

(update-proxy p {"run" (fn [this] (println "v2"))})
(.run p)  ; "v2"
```

*Class reuse.* Multiple proxies can share the same generated class with different behavior—they just have different maps.

*The super trick.* Clojure functions don't have access to Java's `super` keyword. The map-nil trick is how `proxy-super` works at all.

These are reasonable tradeoffs for most use cases. But they break down when you need to intercept recursive internal calls.

---

The fix is to use `gen-class` instead. It's more verbose and requires AOT compilation, but it generates a proper Java method:

```java
public void marshal(Object o, boolean asMapKey, WriteCache cache) {
    if (marshal__var.isBound()) {
        marshal__var.get().invoke(this, o, asMapKey, cache);
    } else {
        super.marshal(o, asMapKey, cache);
    }
}
```

The key difference: it checks a Clojure *var*, not a mutable map. The var stays bound during the entire call, including all recursive internal calls. No nil-ing out.

Our gen-class solution:

```clojure
(gen-class
  :name com.latacora.transit_canon.impl.CanonicalEmitter
  :extends com.cognitect.transit.impl.JsonEmitter
  :exposes-methods {marshal superMarshal}
  :prefix "emitter-")

(defn emitter-marshal [this obj as-map-key cache]
  (if (instance? RawJson obj)
    (.writeRawValue (.getGen this) (.json-str obj))
    (.superMarshal this obj as-map-key cache)))
```

Now every `marshal` call—including the recursive ones from inside `emitArray`—goes through our override.

---

*When does this matter?*

Honestly, rarely. This edge case only bites you when all three conditions are true:

1. You're overriding a method that gets called recursively (directly or through other methods)
2. You need to intercept *all* those recursive calls, not just the top-level one
3. You're using `proxy-super` to delegate to the parent

For most proxy uses—implementing interfaces, callbacks, test mocks—you'll never notice. But if you're trying to intercept internal method dispatch in a class hierarchy, proxy will silently fail and you'll be very confused.

*The rule:* If you need recursive internal calls to hit your override, use `gen-class`.
