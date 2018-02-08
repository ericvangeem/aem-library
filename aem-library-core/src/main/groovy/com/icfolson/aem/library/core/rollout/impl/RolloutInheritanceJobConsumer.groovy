package com.icfolson.aem.library.core.rollout.impl

import com.adobe.granite.offloading.api.OffloadingJobProperties
import com.day.cq.commons.jcr.JcrUtil
import groovy.util.logging.Slf4j
import org.apache.sling.api.resource.ResourceResolverFactory
import org.apache.sling.event.jobs.Job
import org.apache.sling.event.jobs.consumer.JobConsumer
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference

import javax.jcr.Node
import javax.jcr.RepositoryException
import javax.jcr.Session
import javax.jcr.Value
import javax.jcr.util.TraversingItemVisitor

import static com.day.cq.commons.jcr.JcrConstants.NT_UNSTRUCTURED
import static com.icfolson.aem.library.core.rollout.impl.RolloutInheritanceEventListener.EVENT_AFTER_VALUE
import static com.icfolson.aem.library.core.rollout.impl.RolloutInheritanceEventListener.EVENT_BEFORE_VALUE

@Component(service = JobConsumer, property = [
    "job.topics=com/icfolson/aem/library/core/services/job"
])
@Slf4j("LOG")
class RolloutInheritanceJobConsumer implements JobConsumer {

    @Reference
    private ResourceResolverFactory resourceResolverFactory

    private Session session

    @Override
    JobConsumer.JobResult process(Job job) {
        def result = JobConsumer.JobResult.FAILED

        def path = (String) job.getProperty(OffloadingJobProperties.INPUT_PAYLOAD.propertyName())

        def resourceResolver

        try {
            resourceResolver = resourceResolverFactory.getAdministrativeResourceResolver(null)

            session = resourceResolver.adaptTo(Session)

            def jcrContentPath = path[0..(-1 * RolloutInheritanceEventListener.PROPERTY_INHERITANCE_CANCELLED_PATH.length())]

            if (jcrContentPath) {
                session.refresh(true)

                def pageContentNode = session.getNode(jcrContentPath)

                def beforeValues = getDeepProperties(job.getProperty(EVENT_BEFORE_VALUE).split(","), pageContentNode)
                def afterValues = getDeepProperties(job.getProperty(EVENT_AFTER_VALUE).split(","), pageContentNode)

                // remove common values
                Set<String> commonValues = beforeValues.intersect(afterValues as Iterable)
                Set<String> uniqueBeforeValues = beforeValues - commonValues
                Set<String> uniqueAfterValues = afterValues - commonValues

                uniqueAfterValues.each { afterValue ->
                    addOrRemoveProp(pageContentNode, afterValue, true)
                }

                uniqueBeforeValues.each { beforeValue ->
                    addOrRemoveProp(pageContentNode, beforeValue, false)
                }

                result = JobConsumer.JobResult.OK
            }
        } catch (Throwable t) {
            LOG.error("Failed to rollout deep inheritance ${path}", t)
        } finally {
            session?.logout()

            if (resourceResolver && resourceResolver.live) {
                resourceResolver.close()
            }
        }

        result
    }

    private Set<String> getDeepProperties(String[] values, Node pageContentNode) throws RepositoryException {
        def deepProps = new HashSet<String>()

        if (values) {
            values.each { value ->
                if (!value.startsWith("/") && value.lastIndexOf("/") > 0) {
                    deepProps.add(value)
                } else if (pageContentNode.hasNode(value)) {
                    def propertyNode = pageContentNode.getNode(value)

                    def visitor = new TraversingItemVisitor.Default(false) {
                        @Override
                        protected void entering(Node node, int level) throws RepositoryException {
                            def childNodePath = node.path.substring(pageContentNode.path.length() + 1)
                            def propertyIterator = node.properties

                            while (propertyIterator.hasNext()) {
                                def property = propertyIterator.nextProperty()
                                def propertyName = property.name

                                if (!propertyName.startsWith("jcr:") && !propertyName.startsWith("cq:")) {
                                    String deepPropertyPath = "${childNodePath}/${propertyName}"
                                    deepProps.add(deepPropertyPath)
                                }
                            }
                        }
                    }

                    visitor.visit(propertyNode)
                }
            }
        }

        deepProps
    }

    private Value[] getPropertyValues(Node node, String propertyName) throws RepositoryException {
        Value[] values = []

        if (node.hasProperty(propertyName)) {
            def prop = node.getProperty(propertyName)

            if (prop.multiple) {
                values = prop.values
            } else {
                values = [prop.value]
            }
        }

        values
    }

    private void addOrRemoveProp(Node pageContentNode, String propertyValue, boolean addToCancel)
        throws RepositoryException {
        int propertyIndex = propertyValue.lastIndexOf("/")
        String childNodePath = propertyValue.substring(0, propertyIndex)
        String childPropName = propertyValue.substring(propertyIndex + 1)

        LOG.debug("${(addToCancel ? "Adding" : "Removing")} property ${childPropName} " +
            "for ${RolloutInheritanceEventListener.PROPERTY_INHERITANCE_CANCELLED} on child node ${childNodePath}")

        Node childNode

        if (pageContentNode.hasNode(childNodePath)) {
            childNode = pageContentNode.getNode(childNodePath)
        } else {
            childNode = JcrUtil.createPath(pageContentNode, childNodePath, false, NT_UNSTRUCTURED, NT_UNSTRUCTURED,
                session, true)
        }

        Value[] existingChildCancelInheritanceValues = getPropertyValues(childNode,
            RolloutInheritanceEventListener.PROPERTY_INHERITANCE_CANCELLED)

        List<String> updatedChildCancelInheritanceValues = []

        existingChildCancelInheritanceValues.each { Value existingCancelInheritanceValue ->
            String existingCancelInheritanceString = existingCancelInheritanceValue.string

            if (existingCancelInheritanceString != childPropName) {
                updatedChildCancelInheritanceValues.add(existingCancelInheritanceString)
            }
        }

        if (addToCancel) {
            updatedChildCancelInheritanceValues.add(childPropName)
        }

        if (updatedChildCancelInheritanceValues.sort() != existingChildCancelInheritanceValues.sort()) {
            if (updatedChildCancelInheritanceValues.size() > 0) {
                childNode.setProperty(RolloutInheritanceEventListener.PROPERTY_INHERITANCE_CANCELLED,
                    updatedChildCancelInheritanceValues.toArray(new String[updatedChildCancelInheritanceValues.size()]))
            } else {
                childNode.setProperty(RolloutInheritanceEventListener.PROPERTY_INHERITANCE_CANCELLED, (String[]) null)
            }

            session.save()
        }
    }
}
