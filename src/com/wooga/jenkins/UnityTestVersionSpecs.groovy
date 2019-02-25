package com.wooga.jenkins

class UnityTestVersionSpecs implements List<UnityTestVersionSpec> {

    private final List<UnityTestVersionSpec> specs

    UnityTestVersionSpecs() {
        specs = []
    }

    static UnityTestVersionSpecs fromDeclaration(Object versionDeclaration) {
        if( List.isInstance(versionDeclaration)) {
            return fromList(versionDeclaration as List<Object>)
        } else if( String.isInstance(versionDeclaration)) {
            addVersion(versionDeclaration as String)
        } else if( Map.isInstance(versionDeclaration)) {
            addVersion(versionDeclaration as Map)
        }
    }

    static UnityTestVersionSpecs fromList(List<Object> declarations) {
        def specs = new UnityTestVersionSpecs()
        for (Object declaration: declarations) {
            if( String.isInstance(declaration)) {
                specs.add(new UnityTestVersionSpec(declaration as String))
            } else if( Map.isInstance(declaration)) {
                specs.add(UnityTestVersionSpec.fromMap(declaration as Map))
            }
        }
        specs
    }

    static UnityTestVersionSpecs addVersion(String versionReq) {
        def specs = new UnityTestVersionSpecs()
        specs.add(new UnityTestVersionSpec(versionReq))
        specs
    }

    static UnityTestVersionSpecs addVersion(Map declaration) {
        def specs = new UnityTestVersionSpecs()
        specs.add(UnityTestVersionSpec.fromMap(declaration))
        specs
    }

    // List implementation

    @Override
    int size() {
        return specs.size()
    }

    @Override
    boolean isEmpty() {
        return specs.isEmpty()
    }

    @Override
    boolean contains(Object o) {
        return specs.contains(o)
    }

    @Override
    Iterator<UnityTestVersionSpec> iterator() {
        return specs.iterator()
    }

    @Override
    Object[] toArray() {
        return specs.toArray()
    }

    @Override
    def <T> T[] toArray(T[] a) {
        return specs.toArray(a)
    }

    @Override
    boolean add(UnityTestVersionSpec unityTestVersionSpec) {
        return specs.add(unityTestVersionSpec)
    }

    @Override
    boolean remove(Object o) {
        return specs.remove(o)
    }

    @Override
    boolean containsAll(Collection<?> c) {
        return specs.containsAll(c)
    }

    @Override
    boolean addAll(Collection<? extends UnityTestVersionSpec> c) {
        return specs.addAll(c)
    }

    @Override
    boolean addAll(int index, Collection<? extends UnityTestVersionSpec> c) {
        return specs.addAll(index, c)
    }

    @Override
    boolean removeAll(Collection<?> c) {
        return specs.removeAll(c)
    }

    @Override
    boolean retainAll(Collection<?> c) {
        return specs.retainAll(c)
    }

    @Override
    void clear() {
        specs.clear()
    }

    @Override
    UnityTestVersionSpec get(int index) {
        return specs.get(index)
    }

    @Override
    UnityTestVersionSpec set(int index, UnityTestVersionSpec element) {
        return specs.set(index, element)
    }

    @Override
    void add(int index, UnityTestVersionSpec element) {
        specs.add(index, element)
    }

    @Override
    UnityTestVersionSpec remove(int index) {
        return specs.remove(index)
    }

    @Override
    int indexOf(Object o) {
        return specs.indexOf(o)
    }

    @Override
    int lastIndexOf(Object o) {
        return specs.lastIndexOf(o)
    }

    @Override
    ListIterator<UnityTestVersionSpec> listIterator() {
        return specs.listIterator()
    }

    @Override
    ListIterator<UnityTestVersionSpec> listIterator(int index) {
        return specs.listIterator(index)
    }

    @Override
    List<UnityTestVersionSpec> subList(int fromIndex, int toIndex) {
        return specs.subList(fromIndex, toIndex)
    }
}
