
# @RequiredArgsConstructor와 @Qualifier를 함께 쓰기
## @Primary가 있다면 Bean이름으로 주입이 안된다
별도의 설정을 한 RestTemplate Bean을 사용하려는데, 내부 라이브러리에 `@Primary`로 등록된 RestTemplateBean이 있었다.
```java
// osc-spring-boot-starter...
@Bean  
@Primary  
public RestTemplate restTemplate() {  
    RestTemplate restTemplate = new RestTemplate(this.bufferingClientHttpRequestFactory());  
    restTemplate.getInterceptors().add(this.oscClientHttpRequestInterceptor);  
    return restTemplate;  
}
```
위 설정과 다른 Bean을 프로젝트 내부에 생성하고 `@Qualifier`를 통해 주입하던것을 확인했다.
``` java
@Bean  
public RestTemplate targetRestTemplate() {  
  
    if (StringUtils.equals(activeProfile, "local")) {  
        return new RestTemplateBuilder()  
                .setConnectTimeout(Duration.ofSeconds(5))  
                .setReadTimeout(Duration.ofSeconds(120))  
                .additionalInterceptors(new LoggingInterceptor())  
                .requestFactory(this::shinsegaenmBufferingClientHttpRequestFactory)  
                .build();  
    } else {  
        return new RestTemplateBuilder()  
                .setConnectTimeout(Duration.ofSeconds(5))  
                .setReadTimeout(Duration.ofSeconds(120))  
                .additionalInterceptors(new LoggingInterceptor())  
                .requestFactory(this::shinsegaenmBufferingClientHttpRequestFactory)  
                .build();  
    }
    ...
```
`@RequiredArgsConstructor` 를 사용하여 생성자를 주입하는 기존 코드양식에서 `@Qualifier`를 사용해야할 상황이 되어서 아래와 같이 생성자를 다시 주입하는 코드를 짠것으로 보인다.
```java
@RequiredArgsConstructor
@Service
public class TargetService{
	private final InterfaceProperty interfaceProperty;
	private final RestTemplate restTemplate;
	private final HttpServletRequest request;


	public KkoSndngServiceImpl(InterfaceProperty interfaceProperty,  
	                           @Qualifier("targetRestTemplate")  
	                           RestTemplate restTemplate,  
	                           HttpServletRequest request) {  
	    this.interfaceProperty = interfaceProperty;  
	    this.restTemplate = restTemplate;  
	    this.request = request;  
	}
	...
}
```
#### 현재 상황
1. Bean 주입은 @RequiredArgsConstructor + final 을 통해 하고있음
2. 위에 대한 이유로 `@Qualifier`를 직접적으로 필드에서 선언을 못함
3. RestTemplate의 Bean은 내부라이브러리에 `@Primary`로 선언되어있음
4. 별도의 설정을 넣은 RestTemplate Bean을 beanName과 함께 생성함
5. <U>클래스 생성자를 별도로 사용하고 싶지 않음</U>


#### 시도한 방법
1. 주입할 대상의 필드명을 Bean명과 일치시킨다.
```java
@RequiredArgsConstructor
@Service
public class TargetService{
	private final InterfaceProperty interfaceProperty;
//	private final RestTemplate restTemplate;
	private final RestTemplate targetRestTemplate;
	private final HttpServletRequest request;

	...
}
```
   -> 내부라이브러리에 `@Primary` 선언된 Bean 으로인해 BeanName만으론 주입되지 않는다. 만약에 @Primary를 제거한다면, 내가 사용하는 코드는 BeanName으로 주입이 되겠지만.. 기존코드들이 다 영향을 받을것이다.
   
2. 주입하는 필드 위에 @Qualifier선언
```java
@RequiredArgsConstructor
@Service
public class TargetService{
	private final InterfaceProperty interfaceProperty;
	@Qualifier("targetRestTemplate")
	private final RestTemplate restTemplate;
	private final HttpServletRequest request;
	...
}
```
   -> 위와 같이 코드를 작성하니 `@Qualifier` 라인에 warning이 떴고 다음과 같은 inspection이 나왔다.
   `Lombok does not copy the annotation 'org.springframework.beans.factory.annotation.Qualifier' into the constructor`
   생성자를 통해 주입할때 필드 위의 어노테이션은 복사를 하지 않는다고 한다.
   
3. lombok.config 설정을 통한 해결
   구글링을 통해 `@Qualifier` 어노테이션을 `@RequiredArgsConstructor` 생성자주입에 사용하는 방법을 찾았다.
```config
# lombok.config
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier
```
   -> root 하위에 lombok.config 파일을 생성하고, 위 설정을 추가해주면 Qualifier 어노테이션을 복사가 가능하게 된다.




#### Bean주입에 대한 스프링 코드
``` java
// DefaultListableBeanFactory.java
...
@Nullable  
public Object doResolveDependency(DependencyDescriptor descriptor, @Nullable String beanName,  
       @Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {
       ...
    Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);  
if (matchingBeans.isEmpty()) {  
    if (isRequired(descriptor)) {  
       raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);  
    }    return null;  
} // (1)
...
  
if (matchingBeans.size() > 1) {   // (2)
    autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);  
    if (autowiredBeanName == null) {  
       if (isRequired(descriptor) || !indicatesMultipleBeans(type)) {  
          return descriptor.resolveNotUnique(descriptor.getResolvableType(), matchingBeans);  
       }       else {  
       }    }    instanceCandidate = matchingBeans.get(autowiredBeanName);
       
```
[1]타입과 일치하는 Bean이 없다면 주입시 NULL이 발생한다는것을 코드로 확인할 수 있음
[2]일치하는 Bean이 1개 이상일때는 아래 메서드명에서 유추하듯이 후보자들에서 결정함

``` java
@Nullable
protected String determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor descriptor) {
    Class<?> requiredType = descriptor.getDependencyType();
    String primaryCandidate = determinePrimaryCandidate(candidates, requiredType);
    if (primaryCandidate != null) {
        return primaryCandidate;
    }
    String priorityCandidate = determineHighestPriorityCandidate(candidates, requiredType);
    if (priorityCandidate != null) {
        return priorityCandidate;
    }
    // Fallback
    for (Map.Entry<String, Object> entry : candidates.entrySet()) {
        String candidateName = entry.getKey();
        Object beanInstance = entry.getValue();
        if ((beanInstance != null && this.resolvableDependencies.containsValue(beanInstance)) ||
                matchesBeanName(candidateName, descriptor.getDependencyName())) {
            return candidateName;
        }
    }
    return null;
}
```
여러개의 Bean이 생성됬을때는 `@Primary` 가 가장 우선으로 주입됨을 확인 할 수 있다. 그 이후 `@Priority` , BeanName을 통해 후보자들에서 색출해낸다.

#### 검증
RestTemplate Bean을 2개를 만들어보고 소스에서 확인한 내용이 맞는지 확인해본다.
``` java
@Configuration  
public class CustomConfig {  
	@Bean
    public RestTemplate fooRestTemplate(){  
        return new RestTemplateBuilder()  
                .setConnectTimeout(Duration.ofSeconds(10))  
                .build();  
    };  
    @Bean
    public RestTemplate varRestTemplate(){  
        return new RestTemplateBuilder()  
                .setConnectTimeout(Duration.ofSeconds(1))  
                .build();  
    }}
```

1. 동일한 타입의 Bean을 naming을 명시하지 않고 주입하는 경우
```java 
@ExtendWith(SpringExtension.class)  
@SpringBootTest  
@Slf4j  
class CustomConfigTest {  
  
    @Autowired  
    private RestTemplate restTemplate;  
  
    @Test  
    void Bean_Injection_test(){  
        log.info("restTemplate's id ={}",restTemplate);  
    }  
}
```
Intellij의 inspection으로 코드를 돌려도 보기 전에 경고가 나온다.
동일한 Type의 bean이 1개 이상이여서 실행 전에도 코드에 문제가 있다는것을 알 수 있었다.
```
Caused by: org.springframework.beans.factory.NoUniqueBeanDefinitionException: No qualifying bean of type 'org.springframework.web.client.RestTemplate' available: expected single matching bean but found 2: fooRestTemplate,varRestTemplate
```

2. 변수명을 BeanName과 일치시키는 경우
```java 
@Autowired  
private RestTemplate fooRestTemplate;  
  
@Autowired  
private RestTemplate varRestTemplate;  
  
@Test  
void Bean_Injection_test(){  
    log.info("fooRestTemplate's id ={}",fooRestTemplate);  
    log.info("varRestTemplate's id ={}",varRestTemplate);  
}

/**
* varRestTemplate's id =org.springframework.web.client.RestTemplate@5cc1bf20
* fooRestTemplate's id =org.springframework.web.client.RestTemplate@3b218c74
**/
```
소스 내용으로 유추했던것과 정확하게 서로 다른 Bean을 가지고있는걸 확인 할 수 있다.

3. Named Bean과 @Primary Bean이 존재하는 경우
``` java 
@Primary  
public RestTemplate fooRestTemplate(){  
    return new RestTemplateBuilder()  
            .setConnectTimeout(Duration.ofSeconds(10))  
            .build();  
};  
  
@Bean  
public RestTemplate varRestTemplate(){  
    return new RestTemplateBuilder()  
            .setConnectTimeout(Duration.ofSeconds(1))  
            .build();  
}
```
fooRestTemplate을 @Primary로 변경하고 테스트를 진행한다.
``` java
@Autowired  
private RestTemplate fooRestTemplate;  
  
@Autowired  
private RestTemplate varRestTemplate;  
  
@Autowired  
private RestTemplate restTemplate;  
  
@Test  
void Bean_Injection_test(){  
    log.info("fooRestTemplate's id ={}",fooRestTemplate);  
    log.info("varRestTemplate's id ={}",varRestTemplate);  
    log.info("restTemplate's id ={}", restTemplate);  
};
/**
* fooRestTemplate's id =org.springframework.web.client.RestTemplate@2bba35ef
* varRestTemplate's id =org.springframework.web.client.RestTemplate@2bba35ef
* restTemplate's id =org.springframework.web.client.RestTemplate@2bba35ef
**/
```
BeanName과 일치하던지 안하던지 무조건 @Primary의 Bean을 가져오는것을 확인 할 수 있었다.

4. @Primary Bean 주입을 피하기 위한 @Qualifier를 사용하는 경우
``` java
@Autowired  
private RestTemplate fooRestTemplate;  

@Qualifier("varRestTemplate")  
@Autowired  
private RestTemplate varRestTemplate;  
  
   @Test  
void Bean_Injection_test(){  
       log.info("fooRestTemplate's id ={}",fooRestTemplate);  
       log.info("varRestTemplate's id ={}",varRestTemplate);  
}
/**
* fooRestTemplate's id =org.springframework.web.client.RestTemplate@5ba1b62e
* varRestTemplate's id =org.springframework.web.client.RestTemplate@2bba35ef
**/ 
```
의도대로 원하는 필드에 특정 Bean을 주입하는것을 확인 할 수 있었다.
