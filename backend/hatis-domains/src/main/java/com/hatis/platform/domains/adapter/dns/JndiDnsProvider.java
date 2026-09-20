package com.hatis.platform.domains.adapter.dns;

import com.hatis.platform.domains.port.out.DnsProvider;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Read-only DNS provider backed by JNDI resolution.
 *
 * <p>This is the adapter used when the customer keeps their DNS at their own
 * registrar — the common case. It cannot write records ({@link #manages} is always
 * false), and that is the correct answer rather than an unsupported-operation
 * surprise: the platform must not pretend to control a zone it does not control.
 *
 * <p>Resolvers are configurable so a private installation can point at its own
 * recursive servers instead of depending on public DNS.
 */
@Component
@ConditionalOnProperty(name = "hatis.domains.dns.mode", havingValue = "lookup", matchIfMissing = true)
public class JndiDnsProvider implements DnsProvider {

    private static final String LOOKUP_TIMEOUT_MILLIS = "3000";

    private final String resolver;

    public JndiDnsProvider(org.springframework.core.env.Environment environment) {
        this.resolver = environment.getProperty("hatis.domains.dns.resolver");
    }

    @Override
    public String name() {
        return "jndi-lookup";
    }

    @Override
    public boolean manages(String apexDomain) {
        return false;
    }

    @Override
    public void upsertRecord(String apexDomain, String recordName, RecordType type, String value, long ttl) {
        throw new PlatformExceptions.BusinessRuleViolation(
                "This installation does not manage DNS zones; publish the record at your own provider");
    }

    @Override
    public void deleteRecord(String apexDomain, String recordName, RecordType type) {
        throw new PlatformExceptions.BusinessRuleViolation(
                "This installation does not manage DNS zones; remove the record at your own provider");
    }

    @Override
    public List<String> lookup(String recordName, RecordType type) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", LOOKUP_TIMEOUT_MILLIS);
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        if (resolver != null && !resolver.isBlank()) {
            env.put("java.naming.provider.url", "dns://" + resolver);
        }
        try {
            DirContext context = new InitialDirContext(env);
            try {
                Attributes attributes = context.getAttributes("dns:/" + recordName,
                        new String[]{type.name()});
                Attribute attribute = attributes.get(type.name());
                if (attribute == null) {
                    return List.of();
                }
                List<String> values = new ArrayList<>();
                NamingEnumeration<?> all = attribute.getAll();
                while (all.hasMore()) {
                    values.add(String.valueOf(all.next()));
                }
                return values;
            } finally {
                context.close();
            }
        } catch (javax.naming.NameNotFoundException e) {
            // "Record does not exist" is a normal, expected outcome during
            // verification polling — not a dependency failure.
            return List.of();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("dns",
                    "Unable to resolve " + recordName);
        }
    }
}
