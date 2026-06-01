package io.github.dailystruggle.commandsapi.common.localCommands;

import io.github.dailystruggle.commandsapi.common.CommandExecutor;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * a command layer that can call a number of sub-commands, hence creating a tree structure.
 * This enables a typical command chain, where one command has a number of sub-commands
 */
public interface TreeCommand extends CommandsAPICommand {
    default void addParameter(String name, CommandParameter parameter) {
        getParameterLookup().put(name.toLowerCase(),parameter);
    }

    default void addSubCommand(CommandsAPICommand command) {
        getCommandLookup().put(command.name().toUpperCase(),command);
    }

    Map<String, CommandParameter> getParameterLookup();
    Map<String, CommandsAPICommand> getCommandLookup();

    default void msgInvalidCommand(UUID callerId, String argument) {
        //no-op
    }

    default void msgInvalidCommand(UUID callerId, String argument, Consumer<String> messageMethod) {
        msgInvalidCommand(callerId, argument);
    }

    @NotNull
    default List<String> onTabComplete(@NotNull UUID callerId,
                                       @NotNull Predicate<String> permissionCheckMethod,
                                       @NotNull String[] args) {
        return onTabComplete(callerId,permissionCheckMethod,args,0,null);
    }

    @NotNull
    default List<String> onTabComplete(@NotNull UUID callerId,
                                       @NotNull Predicate<String> permissionCheckMethod,
                                       @NotNull String[] args,
                                       int i,
                                       @Nullable Map<String,CommandParameter> tempParameters) {
        if(tempParameters == null) tempParameters = new HashMap<>();
        Set<String> parameterValues = new HashSet<>();
        List<String> possibleResults = new ArrayList<>();
        Map<String, CommandParameter> parameterLookup = getParameterLookup();

        while (i<args.length && args[i].indexOf('=') >= 0) {
            if(i<args.length-1) {
                String[] arr = splitOnParamDelimiter(args[i]);
                parameterValues.add(arr[0]);

                //check for sub-parameters
                CommandParameter parameter = parameterLookup.get(arr[0]);
                if(parameter == null) parameter = tempParameters.get(arr[0]);
                if(parameter != null) {
                    if (arr.length > 1) {
                        String[] subParameters = arr[1].split(",");
                        for (String subParamName : subParameters) {
                            Map<String, CommandParameter> parameterMap = parameter.subParams(subParamName);
                            if (parameterMap == null) continue;
                            tempParameters.putAll(parameterMap);
                        }
                    }
                }
            }
            ++i;
        }

        if(i==args.length) {//last value condition
            if(i>0) { //last value in a chain
                i--;
                int delimiterIdx = args[i].indexOf('=');
                String arg = delimiterIdx > 0 ? args[i].substring(0, delimiterIdx) : args[i];

                if (delimiterIdx < 0) {
                    for (Map.Entry<String, CommandParameter> entry : parameterLookup.entrySet()) {
                        String key = entry.getKey();
                        CommandParameter value = entry.getValue();
                        if(key == null || value == null) continue;
                        String permission = value.permission();
                        if(parameterValues.contains(key)) continue;
                        if(permission==null || permissionCheckMethod.test(permission))
                            possibleResults.add(key + CommandsAPI.parameterDelimiter);
                    }
                    for (Map.Entry<String, CommandParameter> entry : tempParameters.entrySet()) {
                        String key = entry.getKey();
                        CommandParameter value = entry.getValue();
                        if(key == null || value == null) continue;
                        String permission = value.permission();
                        if(parameterValues.contains(key)) continue;
                        if(permission==null || permissionCheckMethod.test(permission))
                            possibleResults.add(key + CommandsAPI.parameterDelimiter);
                    }
                    for (CommandsAPICommand command : getCommandLookup().values()) {
                        if (permissionCheckMethod.test(command.permission())) possibleResults.add(command.name());
                    }
                } else if (parameterLookup.containsKey(arg)) {
                    if(parameterValues.contains(arg)) return new ArrayList<>();
                    String val = args[i].substring(delimiterIdx+1);
                    Set<String> vals = Arrays.stream(val.split(",")).collect(Collectors.toSet());
                    CommandParameter parameter = parameterLookup.get(arg);
                    List<String> relevantValues = new ArrayList<>(parameter.relevantValues(callerId));
                    String front = (args[i].indexOf(',') >= 0)
                            ?  args[i].substring(0,args[i].lastIndexOf(',')) + ","
                            : arg + "=";
                    relevantValues = relevantValues.stream().filter(s -> !vals.contains(s)).map(s -> front + s).collect(Collectors.toList());
                    possibleResults.addAll(relevantValues);
                    for(String v : vals) {
                        Map<String, CommandParameter> subParams = parameter.subParams(v);
                        if (subParams != null) tempParameters.putAll(subParams);
                    }
                } else if (tempParameters.containsKey(arg)) {
                    if(parameterValues.contains(arg)) return new ArrayList<>();
                    String val = args[i].substring(delimiterIdx+1);
                    Set<String> vals = Arrays.stream(val.split(",")).collect(Collectors.toSet());
                    CommandParameter parameter = tempParameters.get(arg);
                    List<String> relevantValues = new ArrayList<>(parameter.relevantValues(callerId));
                    String front = (args[i].indexOf(',') >= 0)
                            ?  args[i].substring(0,args[i].lastIndexOf(',')) + ","
                            : arg + "=";
                    relevantValues = relevantValues.stream().filter(s -> !vals.contains(s)).map(s -> front + s).collect(Collectors.toList());
                    possibleResults.addAll(relevantValues);
                    for(String v : vals) {
                        Map<String, CommandParameter> subParams = parameter.subParams(v);
                        if (subParams != null) tempParameters.putAll(subParams);
                    }
                }
            }
            else { //only value
                for (Map.Entry<String, CommandParameter> entry : parameterLookup.entrySet()) {
                    String key = entry.getKey();
                    CommandParameter value = entry.getValue();
                    if(key == null || value == null) continue;
                    String permission = value.permission();
                    if(permission==null || permissionCheckMethod.test(permission))
                        possibleResults.add(key + CommandsAPI.parameterDelimiter);
                }
                for (Map.Entry<String, CommandParameter> entry : tempParameters.entrySet()) {
                    String key = entry.getKey();
                    CommandParameter value = entry.getValue();
                    if(key == null || value == null) continue;
                    String permission = value.permission();
                    if(permission==null || permissionCheckMethod.test(permission))
                        possibleResults.add(key + CommandsAPI.parameterDelimiter);
                }
                for (CommandsAPICommand command : getCommandLookup().values()) {
                    String permission = command.permission();
                    if (permission == null || permissionCheckMethod.test(permission)) possibleResults.add(command.name());
                }
            }
        }
        else { //not last value
            CommandsAPICommand nextCommand = getCommandLookup().get(args[i].toUpperCase());
            if(nextCommand != null) {
                return nextCommand.onTabComplete(callerId,permissionCheckMethod, args,i+1, tempParameters);
            }
            else {
                for (Map.Entry<String, CommandParameter> entry : parameterLookup.entrySet()) {
                    String key = entry.getKey();
                    CommandParameter value = entry.getValue();
                    if(key == null || value == null) continue;
                    String permission = value.permission();
                    if(parameterValues.contains(key)) continue; //already used prior
                    if(permission==null || permissionCheckMethod.test(permission))
                        possibleResults.add(key + CommandsAPI.parameterDelimiter);
                }
                for (Map.Entry<String, CommandParameter> entry : tempParameters.entrySet()) {
                    String key = entry.getKey();
                    CommandParameter value = entry.getValue();
                    if(key == null || value == null) continue;
                    String permission = value.permission();
                    if(parameterValues.contains(key)) continue; //already used prior
                    if(permission==null || permissionCheckMethod.test(permission))
                        possibleResults.add(key + CommandsAPI.parameterDelimiter);
                }
                for (CommandsAPICommand command : getCommandLookup().values()) {
                    String permission = command.permission();
                    if (permission==null || permissionCheckMethod.test(permission)) possibleResults.add(command.name());
                }
            }
        }

        possibleResults.add("help");
        if(args.length > 0) return possibleResults.stream().filter(s -> s.startsWith(args[args.length-1])).collect(Collectors.toList());
        else return possibleResults;
    }

    default CompletableFuture<Boolean> onCommand(@NotNull UUID callerId,
                              @NotNull Predicate<String> permissionCheckMethod,
                              @NotNull Consumer<String> messageMethod,
                              @NotNull String[] args) {
        return onCommand(callerId,permissionCheckMethod,messageMethod,args,0,null);
    }

    default CompletableFuture<Boolean> onCommand(@NotNull UUID callerId,
                                                 @NotNull Predicate<String> permissionCheckMethod,
                                                 @NotNull Consumer<String> messageMethod,
                                                 @NotNull String[] args,
                                                 int i,
                                                 @Nullable Map<String,CommandParameter> tempParameters) {
        if(tempParameters == null) tempParameters = new HashMap<>();
        if(!permissionCheckMethod.test(permission())) return CompletableFuture.completedFuture(false);
        Map<String,List<String>> parameterValues = new HashMap<>();
        Map<String, CommandParameter> parameterLookup = getParameterLookup();
        for (; i < args.length; i++) {
            String arg = args[i];

            //catch delimiter with no value
            if (arg.endsWith("=")) {
                msgBadParameter(callerId,arg.substring(0,arg.length()-1),"",messageMethod);
                return CompletableFuture.completedFuture(false);
            }

            String[] argSplit = splitOnParamDelimiter(arg);
            if (argSplit.length < 2) {//if it's a sub-command, process the current command and run the subcommand
                if(arg.equalsIgnoreCase("help") && !getCommandLookup().containsKey("HELP")) {
                    help(callerId,permissionCheckMethod).forEach(messageMethod);
                    return CompletableFuture.completedFuture(false);
                }

                CommandsAPICommand subCommand = getCommandLookup().get(arg.toUpperCase());

                //catch bad command
                if (subCommand == null) {
                    msgInvalidCommand(callerId, arg, messageMethod);
                    return CompletableFuture.completedFuture(false);
                }

                //on top of trying subcommand, run current command in case of independent functionality

                CompletableFuture<Boolean> cont = new CompletableFuture<>();
                CommandExecutor commandExecutor = new CommandExecutor(this, callerId, parameterValues, subCommand, messageMethod, cont);
                CommandsAPI.commandPipeline.add(commandExecutor);

                //catch no perms for subcommand
                if (!permissionCheckMethod.test(subCommand.permission())) {
                    return CompletableFuture.completedFuture(false);
                }

                //run subcommand after this command is done
                int finalI = i+1;
                Map<String, CommandParameter> finalTempParameters = tempParameters;
                CompletableFuture<Boolean> res = new CompletableFuture<>();
                // Use `whenComplete` (not `whenCompleteAsync`) so the
                // sub-command continuation runs on the thread that
                // completes `cont` — i.e. the platform-driven
                // `CommandsAPI.execute()` drain thread (REQ-API-ARCH-006).
                // The async variant would dispatch onto
                // `ForkJoinPool.commonPool`, adding an extra thread hop
                // and a Folia thread-context hazard on the menu-redeem
                // path (`/rtp menu token:<...>` -> MenuRedeemSubcommand).
                cont.whenComplete((aBoolean, throwable) -> {
                    if(aBoolean) {
                        CompletableFuture<Boolean> booleanCompletableFuture = subCommand.onCommand(callerId, permissionCheckMethod, messageMethod, args, finalI, finalTempParameters);
                        booleanCompletableFuture.whenComplete((aBoolean1, throwable1) -> res.complete(aBoolean1));
                    }
                });
                return res;
            }

            //otherwise, test and add the current arg to the list of parameters
            String paramName = argSplit[0].toLowerCase();
            CommandParameter currentParameter = parameterLookup.containsKey(paramName)
                    ? parameterLookup.get(paramName)
                    : tempParameters.get(paramName);
            if (currentParameter == null || !permissionCheckMethod.test(currentParameter.permission())) {
                msgBadParameter(callerId,argSplit[0],argSplit[1],messageMethod);
                return CompletableFuture.completedFuture(false);
            }

            String val = argSplit[1];

            //split args by specific delimiter
            //expand reg:<pattern> tokens against the parameter's caller-relevant value set
            //filter according to parameter limiter to guard possible answers
            //collect into list for command experience
            CommandParameter currentParameterFinal = currentParameter;
            List<String> vals =
                    Arrays.stream(val.split(","))
                            .flatMap(token -> expandRegexToken(token, currentParameterFinal, callerId))
                            .distinct()
                            .filter(s -> {
                                Boolean pass = currentParameterFinal.isRelevant.apply(callerId,s);
                                if(!pass) msgBadParameter(callerId,paramName,s,messageMethod);
                                return pass;
                            })
                            .collect(Collectors.toList());

            //only add if there are valid values
            if(!vals.isEmpty())
                parameterValues.putIfAbsent(paramName, vals);
            for(String s : vals) {
                Map<String, CommandParameter> subParameterMap = currentParameter.subParams(s);
                if(subParameterMap == null || subParameterMap.isEmpty()) continue;
                tempParameters.putAll(subParameterMap);
                for(int j = i+1; j < args.length; j++) {
                    String arg2 = args[j];
                    String[] argSplit2 = splitOnParamDelimiter(arg2);
                    if (argSplit2.length < 2) {
                        break;
                    }
                    String paramName2 = argSplit2[0].toLowerCase();
                    CommandParameter currentParameter2 = subParameterMap.get(paramName2);
                    if(currentParameter2 == null) continue;
                    if(!permissionCheckMethod.test(currentParameter2.permission())) continue;

                    String val2 = argSplit2[1];
                    CommandParameter currentParameter2Final = currentParameter2;
                    List<String> vals2 =
                            Arrays.stream(val2.split(","))
                                    .flatMap(token -> expandRegexToken(token, currentParameter2Final, callerId))
                                    .distinct()
                                    .filter(s2 -> {
                                        boolean pass = currentParameter2Final.isRelevant.apply(callerId,s2);
                                        if(!pass) msgBadParameter(callerId,paramName2,s2,messageMethod);
                                        return pass;
                                    })
                                    .collect(Collectors.toList());
                    if(!vals2.isEmpty()) {
                        tempParameters.putIfAbsent(paramName2,currentParameter2);
                        parameterValues.putIfAbsent(paramName2,vals2);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(onCommand(callerId, parameterValues, null, messageMethod));
    }

    /**
     * Splits a token on the first parameter delimiter ({@code =}), limit 2.
     * Single named grammar primitive retained after the {@code :} -> {@code =}
     * migration; the previous sibling helpers {@code containsParamDelimiter} and
     * {@code indexOfParamDelimiter} were inlined as {@code indexOf('=')} calls
     * because they wrapped a one-liner around a now-fixed delimiter.
     */
    static String[] splitOnParamDelimiter(String token) {
        int idx = token.indexOf('=');
        if (idx < 0) return new String[]{token};
        return new String[]{token.substring(0, idx), token.substring(idx + 1)};
    }

    /**
     * Expand a single comma-separated value token. Tokens prefixed with
     * {@code reg:} are treated as Java {@link Pattern}s and expanded against
     * the parameter's caller-relevant value set; literal tokens are passed
     * through unchanged. A malformed pattern falls back to a literal token so
     * a typo does not abort the whole command.
     *
     * <p>Security invariant (commands-api-ADR-001 addendum 2026-05-06):
     * regex expansion <b>must</b> filter through the execute-time validator
     * {@link CommandParameter#isRelevant} directly, not through
     * {@link CommandParameter#relevantValues(UUID)} which is now suggestion-relevance
     * (default permissive) per the Brigadier-bridge addendum. Mixing the two
     * would let a {@code reg:.*} token surface values the caller cannot use,
     * defeating {@link io.github.dailystruggle.commandsapi.common.CommandParameter}'s
     * authorisation contract. This is the same guarantee {@code RegexParameterSecurityTest}
     * pins (S-INJ-1 .. S-INJ-18).
     */
    static Stream<String> expandRegexToken(String token, CommandParameter parameter, UUID callerId) {
        if (token == null) return Stream.empty();
        if (!token.startsWith("reg:")) return Stream.of(token);
        String patternSrc = token.substring("reg:".length());
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternSrc);
        } catch (PatternSyntaxException e) {
            // malformed regex -> treat as literal so the command does not abort
            return Stream.of(token);
        }
        return parameter.values().stream()
                .filter(v -> parameter.isRelevant.apply(callerId, v))
                .filter(v -> pattern.matcher(v).matches());
    }

    default List<String> help(UUID callerId, Predicate<String> permissionCheckMethod) {
        List<String> parents = new ArrayList<>();
        CommandsAPICommand parent = parent();
        while (parent!=null) {
            parents.add(parent.name());
            parent = parent.parent();
        }

        StringBuilder completeCommand = new StringBuilder();
        for(int i = parents.size()-1; i > 0; i--) {
            completeCommand.append(parents.get(i)).append(" ");
        }
        completeCommand.append(name()).append(" ");

        Map<String, CommandsAPICommand> commandLookup = getCommandLookup();
        Map<String, CommandParameter> parameterLookup = getParameterLookup();
        List<String> possibleResults = new ArrayList<>(2+ parameterLookup.size() + commandLookup.size());
        possibleResults.add("Command: " + completeCommand +
                "\n    " + description());

        if(commandLookup.size()>0) {
            possibleResults.add("Subcommands: ");
            for (CommandsAPICommand command : commandLookup.values()) {
                if (!permissionCheckMethod.test(command.permission())) continue;
                possibleResults.add("  - /" + completeCommand + command.name() +
                        "\n    " + command.description());
            }
        }

        if(parameterLookup.size()>0) {
            possibleResults.add("Parameters: ");
            for (Map.Entry<String, CommandParameter> entry : parameterLookup.entrySet()) {
                if (!permissionCheckMethod.test(entry.getValue().permission())) continue;
                possibleResults.add("  - " + entry.getKey() + CommandsAPI.parameterDelimiter +
                        "\n    " + entry.getValue().description());
            }
        }
        return possibleResults;
    }
}
