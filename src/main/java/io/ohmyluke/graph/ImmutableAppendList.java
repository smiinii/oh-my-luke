package io.ohmyluke.graph;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;

/** Immutable persistent list with constant-time append for step-by-step execution. */
final class ImmutableAppendList<E> extends AbstractList<E> {
    private final List<E> base;
    private final ImmutableAppendList<E> previous;
    private final E appended;
    private final int size;

    private ImmutableAppendList(List<E> base) {
        this.base = List.copyOf(base);
        this.previous = null;
        this.appended = null;
        this.size = base.size();
    }

    private ImmutableAppendList(ImmutableAppendList<E> previous, E appended) {
        this.base = null;
        this.previous = Objects.requireNonNull(previous, "previous");
        this.appended = Objects.requireNonNull(appended, "appended");
        this.size = previous.size + 1;
    }

    @SuppressWarnings("unchecked")
    static <E> ImmutableAppendList<E> copyOf(List<E> values) {
        Objects.requireNonNull(values, "values");
        if (values instanceof ImmutableAppendList<?> persistent) {
            return (ImmutableAppendList<E>) persistent;
        }
        return new ImmutableAppendList<>(values);
    }

    static <E> ImmutableAppendList<E> append(List<E> values, E value) {
        return new ImmutableAppendList<>(copyOf(values), value);
    }

    @Override
    public E get(int index) {
        return materialize().get(index);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<E> iterator() {
        return materialize().iterator();
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        return materialize().listIterator(index);
    }

    private List<E> materialize() {
        ArrayList<E> suffix = new ArrayList<>();
        ImmutableAppendList<E> current = this;
        while (current.previous != null) {
            suffix.add(current.appended);
            current = current.previous;
        }
        ArrayList<E> values = new ArrayList<>(size);
        values.addAll(current.base);
        for (int index = suffix.size() - 1; index >= 0; index--) {
            values.add(suffix.get(index));
        }
        return values;
    }
}
