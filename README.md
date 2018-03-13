# amazon-mq-integration

## Build the docker container
for each plain-java application run  
`docker build -t <container name> .`  

## Make your AWS credentials accessable for your bash
run  
`AWS_ACCESS_KEY_ID=$(aws --profile default configure get aws_access_key_id)`  
`AWS_SECRET_ACCESS_KEY=$(aws --profile default configure get aws_secret_access_key)`  
`AWS_REGION=$(aws --profile default configure get region)`  

## Run your Docker container locally with your AWS credentails provided
for each plain-java application run  
`docker run -e AWS_REGION=$AWS_REGION -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY <container name>`  