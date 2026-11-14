package io.github.xdstorage.resource;

/**
 * W-4c (docs/tasks/2.1.0/48): a resource is a persistent storage unit (one file or file group)
 * managed by the engine. An {@code IResource} is a public-facing handle over one
 * {@link IXdStorageResourceObject}; the {@code allResources()} method on
 * {@link XdStorageAbstractResourcesManager} returns unmodifiable collections of these.
 *
 * <p>This is the public alias of the engine-internal {@link IXdStorageResourceObject};
 * it exists so shutdown- and checkpoint-related APIs can reference "a resource" without
 * leaking internal interfaces.
 */
public interface IResource extends IXdStorageResourceObject {
}
