package io.github.xdstorage.code;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.entities.XdBlackHole;
import io.github.xdstorage.entities.XdGalaxy;
import io.github.xdstorage.entities.XdPlanet;
import io.github.xdstorage.entities.XdSatellite;
import io.github.xdstorage.entities.XdStar;
import io.github.xdstorage.entities.XdStarSystem;
import io.github.xdstorage.entities.XdUniverse;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageAbstractIdObserver;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.utils.XdStorageProxyCompilationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JavaBeans contract tests for the runtime-generated storage wrappers
 * (Simple / Unmodifiable / IdObservable): equals/hashCode contracts,
 * getter/setter delegation, toString delegation and wrapper class shape.
 *
 * The generated wrappers extend the entity but keep the state exclusively in
 * the wrapped inner object; equals is strict wrapper-to-wrapper and hashCode
 * delegates to the inner object — these tests pin that transactional invariant.
 */
public class XdStorageWrapperJavaBeansContractTest {

    private IXdStorage storage;
    private IXdStorageTransactionManager transactionManager;
    private IXdStorageTransaction transaction;

    @BeforeEach
    public void setUp() {
        storage = mock(IXdStorage.class);
        //noinspection unchecked,rawtypes
        transactionManager = mock(IXdStorageTransactionManager.class);
        transaction = mock(IXdStorageTransaction.class);
        when(storage.getTransactionManager()).thenReturn(transactionManager);
        //noinspection unchecked,rawtypes
        when(transactionManager.getCurrentTransaction()).thenReturn(transaction);
    }

    // === fixtures ===

    private static ValueEqualsBean bean(Long id, String name, boolean active) {
        final ValueEqualsBean b = new ValueEqualsBean();
        b.setId(id);
        b.setName(name);
        b.setActive(active);
        return b;
    }

    private static ValueChild child(Long id, String label) {
        final ValueChild c = new ValueChild();
        c.setId(id);
        c.setLabel(label);
        return c;
    }

    private ValueEqualsBean newSimpleWrapper(final ValueEqualsBean inner) throws Exception {
        final Class<?> w = XdStorageProxyCompilationEngine.getClassSimpleWrapper(ValueEqualsBean.class);
        return (ValueEqualsBean) w.getConstructor(Object.class, ValueEqualsBean.class, IXdStorage.class)
                .newInstance(null, inner, storage);
    }

    private ValueEqualsBean newUnmodifiableWrapper(final ValueEqualsBean inner) throws Exception {
        final Class<?> w = XdStorageProxyCompilationEngine.getClassUnmodifiableWrapper(ValueEqualsBean.class);
        return (ValueEqualsBean) w.getConstructor(Object.class, ValueEqualsBean.class, IXdStorage.class, IXdStorageTransaction.class)
                .newInstance(null, inner, storage, transaction);
    }

    private IXdStorageIdObservableWrapper newObservableWrapper(final ValueEqualsBean inner) throws Exception {
        final Class<?> w = XdStorageProxyCompilationEngine.getClassObservableWrapper(ValueEqualsBean.class);
        return (IXdStorageIdObservableWrapper) w.getConstructor(ValueEqualsBean.class).newInstance(inner);
    }

    // === Simple wrapper: equals/hashCode contract ===

    @Test
    public void simpleWrapperEqualsContract() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "a", true);
        final ValueEqualsBean b1copy = bean(1L, "a", true);
        final ValueEqualsBean b2 = bean(2L, "b", false);

        final ValueEqualsBean w1 = newSimpleWrapper(b1);
        final ValueEqualsBean w1again = newSimpleWrapper(b1);
        final ValueEqualsBean w1copy = newSimpleWrapper(b1copy);
        final ValueEqualsBean w2 = newSimpleWrapper(b2);

        // reflexive + same inner instance
        assertTrue(w1.equals(w1));
        assertTrue(w1.equals(w1again));

        // equal inner objects (value equals) -> equal wrappers, symmetric
        assertTrue(w1.equals(w1copy));
        assertTrue(w1copy.equals(w1));

        // hashCode must agree with equals (JavaBeans/Map contract)
        assertEquals(w1.hashCode(), w1copy.hashCode());
        assertEquals(w1.hashCode(), b1.hashCode());

        // different inner state -> not equal
        assertFalse(w1.equals(w2));
        assertFalse(w2.equals(w1));

        // strict wrapper-to-wrapper: plain entity, null and foreign types are never equal
        assertFalse(w1.equals(b1));
        assertFalse(w1.equals((Object) null));
        assertFalse(w1.equals("not a wrapper"));

        // dummy wrapper has no inner object -> not equal, no exception
        assertFalse(w1.equals(new XdStorageDummySimpleWrapper()));
    }

    @Test
    public void simpleWrapperEqualityWorksInHashSet() throws Exception {
        final ValueEqualsBean w1 = newSimpleWrapper(bean(1L, "a", true));
        final ValueEqualsBean w1copy = newSimpleWrapper(bean(1L, "a", true));
        final ValueEqualsBean w2 = newSimpleWrapper(bean(2L, "b", false));

        final Set<Object> set = new HashSet<>();
        assertTrue(set.add(w1));
        assertFalse(set.add(w1copy), "wrapper over an equal inner must be a duplicate set element");
        assertEquals(1, set.size());
        assertTrue(set.contains(w1copy));
        assertFalse(set.contains(w2));
        assertFalse(set.contains(bean(1L, "a", true)), "plain entity must not match a wrapper element");
    }

    @Test
    public void simpleWrapperHashCodeAndToStringDelegateToInner() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "a", true);
        final ValueEqualsBean w1 = newSimpleWrapper(b1);

        assertEquals(b1.hashCode(), w1.hashCode());
        assertEquals(b1.toString(), w1.toString());
    }

    // === Unmodifiable wrapper: equals/hashCode contract ===

    @Test
    public void unmodifiableWrapperEqualsContract() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "a", true);
        final ValueEqualsBean b1copy = bean(1L, "a", true);
        final ValueEqualsBean b2 = bean(2L, "b", false);

        final ValueEqualsBean w1 = newUnmodifiableWrapper(b1);
        final ValueEqualsBean w1again = newUnmodifiableWrapper(b1);
        final ValueEqualsBean w1copy = newUnmodifiableWrapper(b1copy);
        final ValueEqualsBean w2 = newUnmodifiableWrapper(b2);

        assertTrue(w1.equals(w1));
        assertTrue(w1.equals(w1again));
        assertTrue(w1.equals(w1copy));
        assertTrue(w1copy.equals(w1));
        assertEquals(w1.hashCode(), w1copy.hashCode());
        assertEquals(w1.hashCode(), b1.hashCode());
        assertFalse(w1.equals(w2));
        assertFalse(w2.equals(w1));
        assertFalse(w1.equals(b1));
        assertFalse(w1.equals((Object) null));
        assertFalse(w1.equals("not a wrapper"));
        assertEquals(b1.toString(), w1.toString());

        // the dummy unmodifiable wrapper implements the marker without an inner object
        assertFalse(w1.equals(new XdStorageDummyUnmodifiableWrapper()));
    }

    @Test
    public void wrapperFamiliesAreMutuallyNotEqual() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "a", true);
        final ValueEqualsBean simple = newSimpleWrapper(b1);
        final ValueEqualsBean unmodifiable = newUnmodifiableWrapper(b1);
        final IXdStorageIdObservableWrapper observable = newObservableWrapper(b1);

        assertFalse(simple.equals(unmodifiable));
        assertFalse(unmodifiable.equals(simple));
        assertFalse(simple.equals(observable));
        assertFalse(observable.equals(simple));
        assertFalse(unmodifiable.equals(observable));
        assertFalse(observable.equals(unmodifiable));
    }

    @Test
    public void getWrappedObjectExposesTheInnerObjectWithoutReflection() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "a", true);

        assertSame(b1, ((IXdStorageSimpleWrapper) newSimpleWrapper(b1)).getWrappedObject__());
        assertSame(b1, ((IXdStorageUnmodifiableWrapper) newUnmodifiableWrapper(b1)).getWrappedObject__());
        assertSame(b1, newObservableWrapper(b1).getWrappedObject__());

        // dummy wrappers have no inner object: equals against them stays false
        assertNull(new XdStorageDummySimpleWrapper().getWrappedObject__());
        assertNull(new XdStorageDummyUnmodifiableWrapper().getWrappedObject__());
    }

    // === Simple wrapper: JavaBeans accessors ===

    @Test
    public void simpleWrapperAccessorsDelegateToInnerObject() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "", false);
        final ValueEqualsBean w1 = newSimpleWrapper(b1);

        w1.setName("delegated");
        assertEquals("delegated", w1.getName());
        assertEquals("delegated", b1.getName(), "simple wrapper setter must mutate the inner object");

        w1.setActive(true);
        assertTrue(w1.isActive());
        assertTrue(b1.isActive());

        assertEquals(Long.valueOf(1L), w1.getId());
        final IXdStorageSimpleWrapper asWrapper = (IXdStorageSimpleWrapper) w1;
        assertEquals(b1.getId(), asWrapper.getObjectId__());
        assertEquals("business:delegated", w1.businessMethod(), "non-accessor public methods delegate through the simple wrapper");
    }

    @Test
    public void simpleWrapperReferenceFlagAndLockAreSelfContained() throws Exception {
        final IXdStorageSimpleWrapper w1 = (IXdStorageSimpleWrapper) newSimpleWrapper(bean(1L, "a", true));

        assertTrue(w1.isReference__(), "fresh wrapper starts as a reference stub");
        w1.setReference__(false);
        assertFalse(w1.isReference__());

        // reentrant lock pairs must not throw and must release cleanly
        w1.lock__();
        w1.lock__();
        w1.unlock__();
        w1.unlock__();
    }

    @Test
    public void simpleWrapperLoadByGetLazilyLoadsWrappedStubThroughStorage() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "a", true);
        final ValueChild innerChild = child(7L, "l7");
        final ValueChild stub = (ValueChild) XdStorageObjectUtils.wrapAsSimpleObject(innerChild, storage, transaction);
        assertNotNull(stub);
        b1.setLazyChild(stub);

        final ValueEqualsBean w1 = newSimpleWrapper(b1);

        final Object loaded = w1.getLazyChild();
        assertSame(stub, loaded);
        verify(storage, times(1)).load(same(stub), same(transaction));

        // second access is served from the loaded flag, no second storage round-trip
        w1.getLazyChild();
        verify(storage, times(1)).load(same(stub), same(transaction));
    }

    @Test
    public void simpleWrapperLoadByGetIgnoresPlainNonWrapperChildren() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "a", true);
        b1.setLazyChild(child(8L, "plain"));

        final ValueEqualsBean w1 = newSimpleWrapper(b1);

        assertEquals("plain", w1.getLazyChild().getLabel());
        verify(storage, times(0)).load(any(Object.class), any(IXdStorageTransaction.class));
    }

    // === Unmodifiable wrapper: accessors are closed ===

    @Test
    public void unmodifiableWrapperAccessorsDelegate() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "view", true);
        final ValueChild c1 = child(7L, "l7");
        b1.setChild(c1);
        final ValueEqualsBean w1 = newUnmodifiableWrapper(b1);

        assertEquals("view", w1.getName());
        assertTrue(w1.isActive());
        assertEquals(Long.valueOf(1L), w1.getId());
        assertEquals(b1.toString(), w1.toString());
        assertEquals(b1.hashCode(), w1.hashCode());

        // strong getter returns an unmodifiable wrapper over the child
        final Object wrappedChild = w1.getChild();
        assertTrue(wrappedChild instanceof IXdStorageUnmodifiableWrapper);
        assertEquals("l7", ((ValueChild) wrappedChild).getLabel());
        assertEquals(c1.hashCode(), wrappedChild.hashCode());
        assertNotSame(c1, wrappedChild);
    }

    @Test
    public void unmodifiableWrapperStrongGetterToleratesNullChild() throws Exception {
        final ValueEqualsBean w1 = newUnmodifiableWrapper(bean(1L, "a", true));
        assertNull(w1.getChild());
    }

    @Test
    public void unmodifiableWrapperSettersThrowAndMarkRollbackOnly() throws Exception {
        final ValueEqualsBean w1 = newUnmodifiableWrapper(bean(1L, "a", true));

        assertThrows(XdStorageRuntimeException.class, () -> w1.setName("x"));
        assertThrows(XdStorageRuntimeException.class, () -> w1.setActive(true));
        assertThrows(XdStorageRuntimeException.class, () -> w1.setChild(child(9L, "l9")));
        // RBCAUSE: the generated setters mark with the site descriptor (site, null cause)
        verify(transaction, times(3)).markRollbackOnly(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull());

        // non-accessor public methods are closed the same way
        assertThrows(XdStorageRuntimeException.class, w1::businessMethod);
        verify(transaction, times(4)).markRollbackOnly(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    public void unmodifiableWrapperLoadByGetEagerlyLoadsThroughStorage() throws Exception {
        final ValueEqualsBean b1 = bean(1L, "a", true);
        final ValueChild plainChild = child(7L, "l7");
        b1.setLazyChild(plainChild);

        final ValueEqualsBean w1 = newUnmodifiableWrapper(b1);

        final Object loaded = w1.getLazyChild();
        verify(storage, times(1)).load(same(plainChild), same(transaction));
        assertTrue(loaded instanceof IXdStorageUnmodifiableWrapper);
        assertEquals("l7", ((ValueChild) loaded).getLabel());

        // the loaded flag suppresses the second storage round-trip
        w1.getLazyChild();
        verify(storage, times(1)).load(same(plainChild), same(transaction));
    }

    @Test
    public void unmodifiableWrapperLoadByGetToleratesNullLazyField() throws Exception {
        final ValueEqualsBean w1 = newUnmodifiableWrapper(bean(1L, "a", true));

        assertNull(w1.getLazyChild());
        verify(storage, times(0)).load(any(Object.class), any(IXdStorageTransaction.class));
    }

    // === Observable wrapper: id observation and read-only surface ===

    @Test
    public void observableWrapperIdSetterNotifiesObservers() throws Exception {
        final ValueEqualsBean inner = bean(null, "reactive", false);
        final IXdStorageIdObservableWrapper ow = newObservableWrapper(inner);

        final List<Object[]> events = new ArrayList<>();
        ow.addObserver(new XdStorageAbstractIdObserver() {
            @Override
            public void onNewIdIsSet(final IXdStorageIdObservableWrapper wrapper, final Object id) {
                events.add(new Object[]{wrapper, id});
            }
        });

        ((ValueEqualsBean) ow).setId(42L);
        assertEquals(1, events.size());
        assertSame(ow, events.get(0)[0]);
        assertEquals(Long.valueOf(42L), events.get(0)[1]);
        assertEquals(Long.valueOf(42L), inner.getId(), "id setter must reach the inner object");
    }

    @Test
    public void observableWrapperSettersDelegateToInnerObject() throws Exception {
        final ValueEqualsBean inner = bean(null, "", false);
        final ValueEqualsBean ow = (ValueEqualsBean) newObservableWrapper(inner);

        ow.setName("observed");
        ow.setActive(true);
        ow.setChild(child(5L, "l5"));

        assertEquals("observed", ow.getName());
        assertEquals("observed", inner.getName(), "observable wrapper setter must not strand state on the wrapper itself");
        assertTrue(inner.isActive());
        assertEquals("l5", ow.getChild().getLabel());
    }

    @Test
    public void observableWrapperClosesNonAccessorMethods() throws Exception {
        final ValueEqualsBean ow = (ValueEqualsBean) newObservableWrapper(bean(null, "x", false));
        assertThrows(XdStorageRuntimeException.class, ow::businessMethod);
    }

    @Test
    public void observableWrapperEqualsAndHashCodeContract() throws Exception {
        final IXdStorageIdObservableWrapper ow1 = newObservableWrapper(bean(1L, "a", true));
        final IXdStorageIdObservableWrapper ow1copy = newObservableWrapper(bean(1L, "a", true));
        final IXdStorageIdObservableWrapper ow2 = newObservableWrapper(bean(2L, "b", false));

        assertTrue(ow1.equals(ow1));
        assertTrue(ow1.equals(ow1copy));
        assertTrue(ow1copy.equals(ow1));
        assertEquals(ow1.hashCode(), ow1copy.hashCode());
        assertFalse(ow1.equals(ow2));
        assertFalse(ow1.equals(bean(1L, "a", true)));
        assertFalse(ow1.equals((Object) null));
    }

    // === generated class shape (Java requirements of the produced classes) ===

    @Test
    public void generatedWrapperClassesHaveExpectedShape() throws Exception {
        final Class<?> simple = XdStorageProxyCompilationEngine.getClassSimpleWrapper(ValueEqualsBean.class);
        assertTrue(Modifier.isPublic(simple.getModifiers()));
        assertTrue(ValueEqualsBean.class.isAssignableFrom(simple));
        assertTrue(IXdStorageSimpleWrapper.class.isAssignableFrom(simple));
        assertNotNull(simple.getConstructor(Object.class, ValueEqualsBean.class, IXdStorage.class));
        assertFalse((boolean) simple.getMethod("isDummySimpleWrapper").invoke(null));

        final Class<?> unmodifiable = XdStorageProxyCompilationEngine.getClassUnmodifiableWrapper(ValueEqualsBean.class);
        assertTrue(Modifier.isPublic(unmodifiable.getModifiers()));
        assertTrue(ValueEqualsBean.class.isAssignableFrom(unmodifiable));
        assertTrue(IXdStorageUnmodifiableWrapper.class.isAssignableFrom(unmodifiable));
        assertNotNull(unmodifiable.getConstructor(Object.class, ValueEqualsBean.class, IXdStorage.class, IXdStorageTransaction.class));
        assertFalse((boolean) unmodifiable.getMethod("isDummyUnmodifiableWrapper").invoke(null));

        final Class<?> observable = XdStorageProxyCompilationEngine.getClassObservableWrapper(ValueEqualsBean.class);
        assertTrue(Modifier.isPublic(observable.getModifiers()));
        assertTrue(ValueEqualsBean.class.isAssignableFrom(observable));
        assertTrue(IXdStorageIdObservableWrapper.class.isAssignableFrom(observable));
        assertNotNull(observable.getConstructor(ValueEqualsBean.class));
    }

    @ParameterizedTest
    @MethodSource("modelEntities")
    public void wrappersAreGeneratedForEveryModelEntity(final Class<?> entity) throws Exception {
        final Class<?> simple = XdStorageProxyCompilationEngine.getClassSimpleWrapper(entity);
        assertFalse((boolean) simple.getMethod("isDummySimpleWrapper").invoke(null),
                "simple wrapper silently degraded to dummy for " + entity.getName());

        final Class<?> unmodifiable = XdStorageProxyCompilationEngine.getClassUnmodifiableWrapper(entity);
        assertFalse((boolean) unmodifiable.getMethod("isDummyUnmodifiableWrapper").invoke(null),
                "unmodifiable wrapper silently degraded to dummy for " + entity.getName());
    }

    private static Stream<Class<?>> modelEntities() {
        return Stream.of(TestGeneratedEntity.class, ComplexEntityForGeneration.class, ValueEqualsBean.class,
                ValueChild.class, XdUniverse.class, XdGalaxy.class, XdStarSystem.class, XdStar.class,
                XdPlanet.class, XdSatellite.class, XdBlackHole.class);
    }

    @Test
    public void unmodifiableWrapperIsGeneratedForIdentifiableObjectBase() throws Exception {
        final Map<Class<?>, Class<?>> generated = XdStorageClassGenerator.generateUnmodifiableWrapper(XdStorageIdentifiableObject.class);
        assertFalse(generated.isEmpty());
        final Class<?> wrapper = generated.get(XdStorageIdentifiableObject.class);
        assertNotNull(wrapper);
        assertTrue(IXdStorageUnmodifiableWrapper.class.isAssignableFrom(wrapper));
    }

    // === base generator: method collection (Java-requirements regression) ===

    public static class BaseOverrideBean {
        @io.github.xdstorage.annotations.XdStorageObjectId
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    public static class SubOverrideBean extends BaseOverrideBean {
        @Override
        public Long getId() { return super.getId(); }
    }

    public static class StaticFinalBean {
        @io.github.xdstorage.annotations.XdStorageObjectId
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public static Long computeId() { return 1L; }
        public final String stable() { return "stable"; }
    }

    private static class NoIdBean {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    public void getPublicMethodsReturnsOverriddenMethodExactlyOnce() {
        final List<Method> methods = Arrays.asList(XdStorageAbstractClassCodeGenerator.getPublicMethods(SubOverrideBean.class));
        final List<Method> getId = new ArrayList<>();
        for (final Method m : methods) {
            if (m.getName().equals("getId")) getId.add(m);
        }
        assertEquals(1, getId.size(), "an overridden getter must be emitted once, with the most-derived declaration");
        assertEquals(SubOverrideBean.class, getId.get(0).getDeclaringClass());
    }

    @Test
    public void getPublicMethodsSkipsStaticAndFinalMethods() {
        final List<Method> methods = Arrays.asList(XdStorageAbstractClassCodeGenerator.getPublicMethods(StaticFinalBean.class));
        final List<String> names = new ArrayList<>();
        for (final Method m : methods) names.add(m.getName());
        assertFalse(names.contains("computeId"), "static methods cannot be overridden by the wrapper");
        assertFalse(names.contains("stable"), "final methods cannot be overridden by the wrapper");
        assertTrue(names.contains("getId"));
    }

    @Test
    public void wrappersCompileForHierarchyAndStaticFinalFixtures() throws Exception {
        final Class<?> sub = XdStorageProxyCompilationEngine.getClassSimpleWrapper(SubOverrideBean.class);
        assertFalse((boolean) sub.getMethod("isDummySimpleWrapper").invoke(null),
                "overridden getter must not break wrapper compilation");

        final Class<?> staticFinal = XdStorageProxyCompilationEngine.getClassSimpleWrapper(StaticFinalBean.class);
        assertFalse((boolean) staticFinal.getMethod("isDummySimpleWrapper").invoke(null),
                "static/final methods must not break wrapper compilation");
    }

    @Test
    public void simpleWrapperGenerationWithoutObjectIdFailsLoudly() {
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> XdStorageClassGenerator.generateSimpleWrapper(NoIdBean.class));
        assertTrue(ex.getMessage().contains("NoIdBean"),
                "failure must name the offending class, got: " + ex.getMessage());
    }

    @Test
    public void collectMethodsClassifiesValueEqualsBeanMembers() {
        final XdStorageSimpleWrapperClassCodeGenerator generator = new XdStorageSimpleWrapperClassCodeGenerator();
        final XdStorageAbstractClassCodeGenerator.GenerationContext ctx =
                generator.collectMethodsAndClasses(ValueEqualsBean.class);

        assertEquals(3, ctx.fieldsGetters.size(), "getId/getName/isActive are simple getters");
        assertEquals(1, ctx.strongGetters.size(), "getChild is the only strong getter");
        assertEquals(1, ctx.strongLoadByGetGetters.size(), "getLazyChild is load-by-get");
        assertEquals(1, ctx.toCloseMethods.size(), "businessMethod is the only closed method");
        assertEquals(5, ctx.setters.size());
        assertNull(ctx.parentGetter);
    }
}
