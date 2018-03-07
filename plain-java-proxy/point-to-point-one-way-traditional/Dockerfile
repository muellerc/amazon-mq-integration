FROM amazonlinux:2017.12

# Install dependencies
RUN yum update -y
RUN yum install -y aws-cli java-1.8.0

# Provision the Proxy
VOLUME /tmp
ADD target/point-to-point-one-way-traditional.jar app.jar

# Run the Proxy
ENTRYPOINT ["java", "-jar", "app.jar"]