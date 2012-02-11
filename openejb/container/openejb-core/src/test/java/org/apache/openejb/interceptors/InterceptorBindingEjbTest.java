package org.apache.openejb.interceptors;

import org.apache.openejb.config.EjbModule;
import org.apache.openejb.jee.Beans;
import org.apache.openejb.jee.EjbJar;
import org.apache.openejb.jee.Interceptor;
import org.apache.openejb.jee.StatelessBean;
import org.apache.openejb.junit.ApplicationComposer;
import org.apache.openejb.junit.Module;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ejb.EJB;
import javax.interceptor.AroundInvoke;
import javax.interceptor.InterceptorBinding;
import javax.interceptor.InvocationContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(ApplicationComposer.class)
public class InterceptorBindingEjbTest {
    @EJB
    private EJB2 ejb2;

    @Test
    public void test() {
        ejb2.foo();
        assertEquals(1, MarkedInterceptor.CLASSES.size());
        assertTrue(MarkedInterceptor.CLASSES.contains(EJB1.class.getSimpleName()));
    }

    @Module
    public EjbModule ejbJar() {
        final EjbJar ejbJar = new EjbJar();
        ejbJar.addInterceptor(new Interceptor(MarkedInterceptor.class));
        ejbJar.addEnterpriseBean(new StatelessBean("ejb1", EJB1.class));
        ejbJar.addEnterpriseBean(new StatelessBean("ejb2", EJB2.class));

        final EjbModule module = new EjbModule(ejbJar);
        final Beans beans = new Beans();
        beans.addInterceptor(MarkedInterceptor.class);
        module.setBeans(beans);
        return module;
    }

    @Inherited
    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface Interception {
    }

    @javax.interceptor.Interceptor
    @Interception
    public static class MarkedInterceptor {
        public static Collection<String> CLASSES = new ArrayList<String>();

        @AroundInvoke
        public Object intercept(InvocationContext invocationContext) throws Exception {
            CLASSES.add(invocationContext.getTarget().getClass().getSimpleName());
            return invocationContext.proceed();
        }
    }

    public static class EJB1 {
        @Interception
        public void foo() {
        }
    }

    public static class EJB2 {
        @EJB
        private EJB1 ejb1;

        public String foo() {
            ejb1.foo();
            return "ok";
        }
    }
}
