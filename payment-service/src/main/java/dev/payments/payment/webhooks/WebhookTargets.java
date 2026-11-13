package dev.payments.payment.webhooks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;

@Component
public class WebhookTargets {
    private final Map<URI,URI> allowed=new HashMap<>();
    public WebhookTargets(@Value("${webhooks.allowed-urls:}") String configured) {
        for(String value:configured.split(","))if(!value.isBlank()){URI uri=parse(value.trim());allowed.put(uri,uri);}
    }
    public URI requireAllowed(String value) {
        var target=allowed.get(parse(value));
        if(target==null)throw new IllegalArgumentException("Callback URL is not approved");return target;
    }
    static URI parse(String value) {
        if(value==null || value.length()>2048)throw new IllegalArgumentException("Invalid callback URL");
        URI uri=URI.create(value).normalize();String scheme=uri.getScheme();
        if(scheme==null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost()==null
            || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getRawQuery()!=null
            || uri.getPort()==0 || uri.getPort()>65535 || uri.getPort()< -1 || uri.getRawPath().isEmpty())
            throw new IllegalArgumentException("Invalid callback URL");
        return uri;
    }
}
